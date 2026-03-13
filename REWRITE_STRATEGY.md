# Namma Yatri Transit Routing: Rewrite Strategy for Performance

## Executive Summary

The current stack uses **OpenTripPlanner (OTP) v2.5** — a 2,925-file Java monolith — deployed via the **nandi** repo as Docker images with 10GB heap (`-Xmx10G`) and 17-18GB memory reservations per pod. At 80 pods on AWS EKS for 7 Indian cities (Chennai, Bangalore, Delhi, Kolkata, Mumbai, Kochi, Bhubaneswar), the resource consumption is extreme and response times are slow.

A targeted rewrite in Rust can realistically achieve **5-15x resource reduction** (from 80 pods to 5-15) and **3-10x latency improvement** on routing queries.

---

## 1. Current Architecture Analysis

### 1.1 OpenTripPlanner (nammayatri/OpenTripPlanner)

- **Language**: Java 21 on JVM (2,925 source files)
- **Version**: Fork of OTP v2.5.0 with 13 Namma Yatri-specific commits
- **Core Algorithms**:
  - **RAPTOR** (Range Raptor + Multi-criteria): Transit routing
  - **A\***: Street/pedestrian routing
  - Filter chain for itinerary post-processing
  - Transfer optimization
- **Data Formats**: GTFS (bus/metro/suburban schedules), OSM (.pbf street network)
- **API**: GraphQL (GTFS schema + Transmodel schema), REST API
- **NammaYatri Customizations** (13 commits on top of upstream):
  - Car transit mode support (`car_transit`, `car_pickup`)
  - OSRM integration bypass for walk mode
  - Entrance/exit fields for station navigation
  - Nearby stops filtering by transit modes
  - Path generation even with existing pathways/connections
  - Long-distance walk removal
  - Non-optimized route parameter
  - Transfer leg negative distance fix
  - Access/egress duration retry logic

### 1.2 Nandi (nammayatri/nandi)

- **Purpose**: Build pipeline + deployment config for OTP
- **What it does**:
  - Maintains GTFS static data and OSM data for 7 cities
  - Python scripts to generate GTFS from city transport authority CSV data
  - JS scripts for stop mapping and GeoJSON generation
  - Multi-stage Docker builds: Maven build -> graph build -> runtime
  - Per-city Dockerfiles (Bangalore, Delhi, Kolkata, etc.)
- **Data pipeline**: CSV data -> Python GTFS generators -> GTFS zip files -> OTP graph build -> Docker image
- **Deployment**: Docker on AWS EKS, per-city configurations
- **Key configs**:
  - `ParallelRouting` enabled
  - Custom OSM tag mappings per city (Chennai, Bangalore)
  - Board/alight slack per mode (bus 5m/2m, car 10m/2m)

### 1.3 Resource Profile (Why 80 Pods?)

| Factor | Impact |
|--------|--------|
| JVM heap: 10GB per instance | ~10GB baseline per pod |
| Memory reservation: 17-18GB per pod | OS + GC overhead + graph objects |
| Per-city separate graph builds | 7 cities = 7x multiplication |
| Graph object in-memory (monolithic) | Full street + transit network loaded in RAM |
| JVM GC pressure | Known OTP issue: memory grows over time ([#2574](https://github.com/opentripplanner/OpenTripPlanner/issues/2574), [#3273](https://github.com/opentripplanner/OpenTripPlanner/issues/3273)) |
| Horizontal scaling for throughput | Multiple replicas per city for load |

**Estimated breakdown**: 7 cities x ~4 replicas each + load balancers + overhead = ~80 pods

---

## 2. Rewrite Options

### Option A: Full Rewrite in Rust (Recommended)

Build a custom Rust transit routing engine tailored to Namma Yatri's needs.

#### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   nandi-router (Rust)                     │
│                                                           │
│  ┌──────────┐  ┌──────────────┐  ┌────────────────────┐ │
│  │ GTFS     │  │ OSM Street   │  │ GraphQL/REST API   │ │
│  │ Loader   │  │ Graph Builder│  │ (async-graphql +   │ │
│  │          │  │              │  │  axum)             │ │
│  └────┬─────┘  └──────┬───────┘  └────────┬───────────┘ │
│       │               │                    │             │
│  ┌────▼───────────────▼────────────────────▼───────────┐ │
│  │           Unified Transit + Street Graph             │ │
│  │    (memory-mapped, multi-city, shared process)       │ │
│  └────────────────────┬────────────────────────────────┘ │
│                       │                                   │
│  ┌────────────────────▼────────────────────────────────┐ │
│  │              Routing Engine                          │ │
│  │  ┌─────────┐  ┌──────────┐  ┌───────────────────┐  │ │
│  │  │ RAPTOR  │  │ A*/CH    │  │ Transfer           │  │ │
│  │  │ (Range  │  │ (Street  │  │ Optimization +     │  │ │
│  │  │  + Mc)  │  │  routing)│  │ Filter Chain       │  │ │
│  │  └─────────┘  └──────────┘  └───────────────────┘  │ │
│  └─────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

#### Key Design Decisions

1. **Memory-mapped timetables** (`memmap2`): Load GTFS timetable data directly from disk-mapped files. Eliminates JVM heap bloat. Multiple processes can share the same mapped file. Proven by [Solari](https://github.com/ellenhp/solari) to work at continental scale with ~3GB for 500 GTFS feeds.

2. **Multi-city single process**: Instead of 7 separate OTP instances, one Rust process can serve all 7 Indian cities from a single memory-mapped graph, with city-aware routing.

3. **Zero-copy data structures**: Use `rkyv` or `flatbuffers` for the transit graph — no deserialization cost, instant startup.

4. **Async I/O with tokio + axum**: Handle thousands of concurrent requests per core without thread-per-request overhead.

5. **Contraction Hierarchies (CH)** for street routing instead of plain A*, giving 100-1000x speedup on street-only queries.

#### Rust Crate Ecosystem

| Component | Crate | Purpose |
|-----------|-------|---------|
| HTTP server | `axum` + `tokio` | Async web framework |
| GraphQL | `async-graphql` | Schema-first GraphQL |
| GTFS parsing | `gtfs-structures` | GTFS feed loader |
| OSM parsing | `osmpbf` | PBF file reader |
| Memory mapping | `memmap2` | mmap timetables |
| Serialization | `rkyv` / `bincode` | Zero-copy graph format |
| Geo operations | `geo` + `rstar` | Spatial indexing |
| Logging | `tracing` | Structured logging |

#### Pros
- **Maximum performance**: No GC pauses, no JVM overhead, cache-friendly data layout
- **Massive memory reduction**: Memory-mapped timetables + no object header overhead = 5-10x smaller memory footprint
- **Single binary deployment**: One ~50MB binary replaces 18GB Docker images
- **All cities in one process**: Shared street graph + per-city GTFS = eliminates pod multiplication
- **Predictable latency**: No GC stop-the-world pauses
- **Fine-grained control**: Can optimize hot paths exactly as needed (SIMD, prefetching, etc.)

#### Cons
- **Highest development effort**: 4-6 months for core routing, 2-3 more for feature parity
- **RAPTOR is hard to implement correctly**: OTP's implementation is battle-tested over 10+ years
- **Need to reimplement NammaYatri customizations**: 13 custom commits worth of business logic
- **Smaller talent pool**: Rust developers are less common than Java
- **Testing burden**: Need comprehensive test suite for routing correctness

#### Estimated Resource Savings
- From 80 pods (17-18GB each) to ~3-5 pods (2-4GB each)
- **Memory**: 1,360 GB -> ~12-20 GB (70-99% reduction)
- **CPU**: Routing 5-10x faster means fewer pods needed for throughput

---

### Option B: Adopt MOTIS (C++ Engine) with Rust API Layer

Use [MOTIS](https://github.com/motis-project/motis) / [Nigiri](https://github.com/motis-project/nigiri) as the core routing engine, with a Rust or Go API adapter layer.

#### Architecture

```
┌────────────────────────────┐    ┌──────────────────────┐
│  Rust/Go API Gateway       │───▶│  MOTIS (C++)          │
│  (GraphQL compatibility    │    │  - Nigiri RAPTOR      │
│   + NammaYatri business    │    │  - OSR street routing  │
│   logic)                   │    │  - Memory-efficient    │
│                            │◀───│  - <2GB for full       │
│                            │    │    timetable           │
└────────────────────────────┘    └──────────────────────┘
```

MOTIS is a production-grade C++ multimodal routing system with:
- **Nigiri core**: RAPTOR with data-oriented, cache-local design
- **Full-year timetables in <2GB RAM**, loads in <2 minutes
- **Planet-scale capability**: Single server handles entire countries
- **REST API** with OpenAPI spec
- **GTFS + GTFS-RT + OSM support**

#### Pros
- **Proven at scale**: Handles planet-scale routing (Transitous runs global MOTIS)
- **Extremely memory efficient**: Data-oriented design, <2GB for full timetable
- **Much faster to deploy**: Weeks, not months
- **Active development**: Regular releases, responsive maintainers
- **GTFS-RT support built-in**: Real-time updates already work

#### Cons
- **C++ dependency**: Harder to customize than pure Rust
- **API mismatch**: Need adapter layer for OTP GraphQL API compatibility
- **Less control over internals**: Customizing RAPTOR behavior requires C++ work
- **NammaYatri customizations**: Need to reimplement car_transit mode, station navigation, etc. in the adapter
- **Deployment complexity**: C++ binary + Rust adapter = two-component system

#### Estimated Resource Savings
- From 80 pods to ~5-8 pods (MOTIS + API layer)
- **Memory**: 1,360 GB -> ~20-40 GB (95-97% reduction)

---

### Option C: Adopt Solari (Rust Transit Engine) + Extend

Use [Solari](https://github.com/ellenhp/solari) as a starting point and extend it.

#### Pros
- **Already Rust**: RAPTOR implemented with all pruning rules
- **Memory-mapped timetables**: Planet-scale on modest hardware
- **Proven fast**: Barcelona-to-Berlin in ~1 second
- **MIT licensed**: Can fork and customize freely

#### Cons
- **Immature**: No GTFS-RT, unstable API, incomplete documentation
- **Missing features**: No equivalent to OTP's filter chain, transfer optimization, multi-criteria search
- **Single developer project**: Bus factor = 1
- **No street routing**: Would need to add A*/CH for walk/car routing
- **No GraphQL API**: Only basic REST
- **Would need significant extension**: Almost as much work as Option A, but building on less-tested foundations

#### Estimated Resource Savings
- Similar to Option A: 80 pods -> 3-5 pods

---

### Option D: Optimize Current OTP (Incremental)

Keep Java OTP but aggressively optimize.

#### Approaches
- **GraalVM Native Image**: AOT compile OTP to native binary (eliminates JVM startup, reduces memory ~50%)
- **Shared graph server**: One graph-building pod, multiple routing pods with shared memory
- **Per-city pod right-sizing**: Most Indian cities need far less than 10GB heap
- **JVM tuning**: ZGC/Shenandoah GC, compressed oops, G1 tuning
- **Graph partitioning**: Load only relevant subgraph per request

#### Pros
- **Lowest risk**: Keep existing battle-tested code
- **Fastest to implement**: Weeks of tuning, not months of rewriting
- **Maintains upstream compatibility**: Easy to merge OTP updates
- **No new skills needed**: Existing Java team can do this

#### Cons
- **Limited ceiling**: JVM has inherent overhead; maybe 2-3x improvement, not 10x
- **Known OTP memory leaks**: Upstream issues [#2574](https://github.com/opentripplanner/OpenTripPlanner/issues/2574), [#3273](https://github.com/opentripplanner/OpenTripPlanner/issues/3273) remain
- **Object overhead**: Java object headers (12-16 bytes each) on millions of graph nodes
- **GraalVM native image**: OTP uses heavy reflection — may not compile cleanly
- **Doesn't solve architectural issue**: Per-city deployment model is inherently wasteful

#### Estimated Resource Savings
- From 80 pods to ~30-40 pods (2-3x improvement)
- **Not enough to reach 10x target**

---

## 3. Recommended Strategy: Option A (Phased Rust Rewrite)

### Why Option A?

| Criterion | A (Rust) | B (MOTIS) | C (Solari) | D (Optimize) |
|-----------|----------|-----------|------------|---------------|
| Resource reduction | 10-15x | 5-10x | 10-15x | 2-3x |
| Latency improvement | 5-10x | 5-10x | 5-10x | 2-3x |
| Customization ease | High | Medium | Medium | High |
| Dev effort | 6-9 months | 2-4 months | 5-8 months | 1-2 months |
| Long-term maintainability | High | Medium | Low | Medium |
| Risk | Medium | Low | High | Low |
| 10x target achievable? | Yes | Likely | Yes | No |

**Option A is recommended** because:
1. Namma Yatri's customizations (car_transit, station navigation, fare stages) require deep control over the routing engine
2. Multi-city deployment in India is a unique requirement that benefits from a unified architecture
3. Long-term ownership of the routing engine avoids dependency on upstream Java OTP releases
4. The Rust ecosystem for transit routing is mature enough (GTFS parsers, OSM readers, spatial indexing)

### Phase 1: Foundation + Street Routing (Months 1-2)

**Goal**: Replace A* street routing with a Rust-based Contraction Hierarchies engine.

**Deliverables**:
- OSM PBF parser and street graph builder
- Contraction Hierarchies preprocessing + query engine
- Walk, bike, car routing on street network
- HTTP API for street-only routing (compatible with current OTP API contract)
- Benchmark suite vs current OTP street routing

**Can run alongside OTP**: Deploy as a sidecar for street-only queries while OTP handles transit.

**Key Rust crates**: `osmpbf`, `axum`, `tokio`, `rstar`

### Phase 2: RAPTOR Transit Routing (Months 2-4)

**Goal**: Implement Range Raptor with multi-criteria search.

**Deliverables**:
- GTFS loader and timetable builder (memory-mapped via `memmap2`)
- Standard Raptor (single criterion: arrival time)
- Range Raptor (time window search)
- Multi-criteria Range Raptor (arrival time + transfers + duration + generalized cost)
- Heuristic optimization (forward Raptor as pruning for McRR)
- Multi-city timetable loading (all 7 cities in one process)
- Integration with street routing for first/last mile

**Validation**: Compare outputs against OTP for same GTFS data + same queries. Must match within acceptable tolerance.

### Phase 3: API Compatibility + NammaYatri Features (Months 4-5)

**Goal**: Full API compatibility with current OTP GraphQL schema.

**Deliverables**:
- GraphQL API (GTFS schema) using `async-graphql`
- Transfer optimization
- Itinerary filter chain
- NammaYatri-specific features:
  - Car transit mode
  - Station entrance/exit navigation
  - Nearby stops by transit mode
  - Fare stage information in stop headsigns
  - Board/alight slack per mode
  - Long-distance walk removal
  - Custom OSM tag mappings (Chennai, Bangalore)

### Phase 4: Data Pipeline + Deployment (Months 5-6)

**Goal**: Replace nandi build pipeline with Rust-native tooling.

**Deliverables**:
- Rust-based graph builder (replaces multi-stage Docker Maven build)
- Pre-built graph format (memory-mappable, zero deserialization)
- Single Docker image (~100MB vs current multi-GB)
- Kubernetes deployment configs
- Graph hot-reload (update GTFS without restart)
- Health checks, metrics (Prometheus), distributed tracing

### Phase 5: Production Migration (Months 6-8)

**Goal**: Shadow deployment, validation, cutover.

**Deliverables**:
- Shadow traffic routing (both OTP and Rust engine, compare results)
- Load testing at 2x peak traffic
- Gradual traffic shift: 10% -> 25% -> 50% -> 100%
- Monitoring dashboards
- Runbook and on-call documentation

---

## 4. Risk Mitigation

| Risk | Mitigation |
|------|------------|
| RAPTOR correctness bugs | Extensive test suite from OTP test data; shadow traffic comparison |
| Rust hiring difficulty | Start with 2-3 senior Rust devs; rest can learn Rust during Phase 1 |
| Timeline overrun | Option D (JVM optimization) as immediate relief while Rust rewrite progresses |
| GTFS data quality issues | Keep existing Python GTFS generators in nandi unchanged |
| Feature parity gaps | Maintain OTP as fallback for edge-case queries during Phase 5 |

### Hybrid Quick-Win Strategy

While the Rust rewrite is in progress (Phase 1-2), **immediately apply Option D optimizations** to the current OTP stack:

1. **Right-size JVM heap per city**: Chennai (large) gets 8GB, Kochi (small) gets 2GB
2. **Use ZGC**: Switch to ZGC garbage collector for lower pause times
3. **Pod autoscaling**: Scale down during off-peak hours
4. **Consolidate small cities**: Bhubaneswar + Kochi + Mumbai metro-only can share one OTP instance

This can reduce pods from 80 to ~40-50 immediately while the Rust engine matures.

---

## 5. Benchmarking Plan

### Metrics to Track

| Metric | Current (estimated) | Target |
|--------|-------------------|--------|
| p50 routing latency | 500-2000ms | 50-200ms |
| p99 routing latency | 3-10s | 500ms |
| Memory per city | 17-18GB | 1-2GB |
| Total cluster memory | ~1,360GB | <100GB |
| Total pods | 80 | 5-10 |
| Graph build time | 15-30 min | 2-5 min |
| Docker image size | 2-5GB | <100MB |
| Cold start time | 2-5 min (JVM + graph load) | <30s (mmap) |

### Benchmark Queries
- Chennai bus: Tambaram to T Nagar (common commute)
- Bangalore metro: Majestic to Whitefield (cross-city)
- Delhi multimodal: Noida to IGI Airport (bus + metro)
- Kolkata: Howrah to Salt Lake (bus + metro + suburban)
- Stress test: 1000 concurrent routing requests

---

## 6. Alternative Language Consideration

### Why Rust over Go/C++?

| Factor | Rust | Go | C++ |
|--------|------|-----|-----|
| Memory efficiency | Best (zero-cost abstractions) | Good (GC, but efficient) | Best |
| Concurrency | Excellent (async + Send/Sync) | Excellent (goroutines) | Good (manual) |
| Safety | Memory-safe at compile time | GC-safe | Unsafe (manual memory) |
| Transit ecosystem | Good (gtfs-structures, osmpbf) | Limited | Good (MOTIS, Valhalla) |
| Build/deploy | Single static binary | Single binary | Complex build chains |
| Performance ceiling | Highest | ~80% of Rust/C++ | Highest |
| Developer experience | Steep learning curve | Easy | Moderate |

**Go** would be a reasonable alternative if the team has more Go experience. It would achieve ~60-80% of Rust's performance gains with faster development. However, Go's GC means you won't fully eliminate the memory pressure issue — just reduce it significantly.

**C++** achieves the same performance as Rust but without memory safety guarantees, making it higher risk for a new codebase.

**Rust is recommended** for this use case because the primary bottleneck is memory (JVM object overhead, GC pressure) and Rust eliminates these at the language level.

---

## 7. Summary

| Option | Effort | Resource Savings | Latency Improvement | Recommendation |
|--------|--------|-----------------|---------------------|----------------|
| **A: Rust rewrite** | 6-9 months | 10-15x | 5-10x | **Recommended** |
| B: MOTIS + adapter | 2-4 months | 5-10x | 5-10x | Good fallback |
| C: Extend Solari | 5-8 months | 10-15x | 5-10x | Too risky |
| D: Optimize JVM | 1-2 months | 2-3x | 2-3x | Do immediately as bridge |

**Recommended path**: Start Option D immediately (2-4 weeks) to cut pods from 80 to ~40. In parallel, begin Option A (Rust rewrite) with Phase 1 street routing. If Rust Phase 2 (RAPTOR) proves harder than expected, pivot to Option B (MOTIS) as a fallback.
