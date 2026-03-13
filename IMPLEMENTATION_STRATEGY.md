# Namma Yatri Transit Router: Comprehensive Rust Rewrite Implementation Strategy

## Version: 2.0 (Revised after dual independent reviews)

---

## Executive Summary

This document is the **definitive implementation strategy** for rewriting the Namma Yatri transit routing engine from Java (OpenTripPlanner v2.5) to Rust. It supersedes the previous `REWRITE_STRATEGY.md` with critical corrections identified by two independent architecture reviews.

**Key corrections from v1.0:**
1. **Timeline**: Revised from 6-8 months to **14-18 months** (phased, with incremental value delivery)
2. **Street routing**: Use OSRM as external service instead of building Contraction Hierarchies from scratch
3. **Multi-city**: Per-city process isolation, not single-process (blast radius protection)
4. **GTFS-RT**: Added as first-class requirement with two-layer architecture (static mmap + realtime overlay)
5. **Concurrency**: rayon compute pool for CPU-bound routing + tokio for I/O (not spawn_blocking)
6. **Graph format**: CSR (Compressed Sparse Row) struct-of-arrays, not petgraph
7. **Incremental delivery**: Every 2-3 months delivers standalone production value

---

## 1. Current State Analysis

### 1.1 What We're Replacing

| Component | Current | Problems |
|-----------|---------|----------|
| **Routing Engine** | OTP v2.5 (Java 21, 2925 files) | 10GB heap, GC pauses, slow startup |
| **Deployment** | 80 pods on AWS EKS (7 cities) | 1,360 GB total memory |
| **Data Pipeline** | nandi repo (Python/JS GTFS generators) | Complex, undocumented transformations |
| **API** | GraphQL (GTFS schema) at `/nandi/otp/gtfs/v1` | Coupled to OTP internals |
| **Build** | Multi-stage Docker (Maven → graph build → runtime) | 2-5GB images, 15-30 min builds |

### 1.2 Cities and Scale

| City | Modes | Relative Complexity | Priority |
|------|-------|-------------------|----------|
| Chennai | Metro, Bus, Suburban Rail | High | P0 (pilot city) |
| Bangalore | Metro, Bus | High | P0 |
| Delhi | Metro, Bus | High | P1 |
| Kolkata | Metro, Bus | Medium | P1 |
| Mumbai | Metro, Suburban | Medium | P1 |
| Kochi | Metro | Low | P2 |
| Bhubaneswar | Bus | Low | P2 |

### 1.3 NammaYatri Custom Features (13 commits, all must be preserved)

1. `CAR_TRANSIT` mode — driving to transit stations within same trip
2. `car_pickup` state tracking on street edges
3. OSRM bypass for walk mode access/egress
4. Station entrance/exit coordinates on Leg objects
5. Nearby stops filtering by transit mode
6. Path generation with existing pathways/connections
7. Long-distance walk removal (>threshold)
8. Non-optimized route parameter
9. Transfer leg negative distance fix
10. Access/egress duration retry logic with fallback
11. Bangalore OSM tag mapper (20-70 km/h road speeds)
12. Chennai OSM tag mapper (15-80 km/h road speeds)
13. URL rewriting (`/otp/` → `/nandi/otp/`)

---

## 2. Target Architecture

### 2.1 High-Level Design

```
                          ┌─────────────────────────────────┐
    GraphQL/REST          │         nx-api (axum)            │
    Requests  ──────────► │   Request parsing, validation    │
                          │   Response formatting            │
                          └───────────┬─────────────────────┘
                                      │ RoutingRequest
                          ┌───────────▼─────────────────────┐
                          │       nx-router                   │
                          │  Orchestrates transit + street    │
                          │  Applies filter chain             │
                          │  Transfer optimization            │
                          └──┬──────────────┬───────────────┘
                             │              │
              ┌──────────────▼──┐    ┌──────▼──────────────┐
              │   nx-raptor     │    │   OSRM (sidecar)    │
              │  Range RAPTOR   │    │   Street routing     │
              │  McRAPTOR       │    │   Walking/cycling    │
              │  (CPU-bound,    │    │   Car routing        │
              │   rayon pool)   │    │   (HTTP or FFI)      │
              └──────┬──────────┘    └─────────────────────┘
                     │
              ┌──────▼──────────────────────────────────────┐
              │              nx-graph                         │
              │   Static: rkyv + memmap2 (CSR layout)        │
              │   Realtime: ArcSwap<RealtimeOverlay>          │
              │   Per-city isolation                          │
              └──────────────────────────────────────────────┘
```

### 2.2 Deployment Model

```
Kubernetes Cluster (AWS EKS)
├── namespace: namma-transit
│   ├── deployment/chennai-router     (2-3 replicas, 512MB-1GB each)
│   ├── deployment/bangalore-router   (2-3 replicas)
│   ├── deployment/delhi-router       (2-3 replicas)
│   ├── deployment/kolkata-router     (1-2 replicas)
│   ├── deployment/mumbai-router      (1-2 replicas)
│   ├── deployment/kochi-router       (1 replica)
│   ├── deployment/bhubaneswar-router (1 replica)
│   ├── deployment/osrm-india         (2-3 replicas, shared)
│   └── service/transit-gateway       (routes by city header/param)
│
│   Total: ~15-20 pods at 512MB-1GB each = 10-20 GB
│   (vs current: 80 pods at 17-18GB each = 1,360 GB)
```

**Why per-city isolation (not single process):**
- Blast radius: Corrupted GTFS for Bhubaneswar cannot take down Mumbai
- Independent scaling: Mumbai needs 10x capacity of Kochi
- Independent deployment: Update Delhi transit data without restarting all cities
- GTFS reload: Memory spike during graph rebuild isolated to one city
- Incremental migration: Roll out one city at a time

---

## 3. Crate Architecture

### 3.1 Workspace Layout

```
namma-transit/
├── Cargo.toml                     # [workspace]
├── crates/
│   ├── nx-model/                  # Zero-dependency domain types
│   │   ├── src/
│   │   │   ├── lib.rs
│   │   │   ├── gtfs.rs            # Stop, Route, Trip, StopTime, Calendar
│   │   │   ├── graph.rs           # StopIdx, RouteIdx, TripIdx (newtype indices)
│   │   │   ├── geo.rs             # Coordinate, BoundingBox, Distance
│   │   │   ├── time.rs            # Timestamp, ServiceDate, TimeWindow
│   │   │   ├── mode.rs            # TransitMode, StreetMode (incl. CAR_TRANSIT)
│   │   │   └── error.rs           # ValidationError (no dependencies)
│   │   └── Cargo.toml             # deps: serde, rkyv ONLY
│   │
│   ├── nx-gtfs/                   # GTFS static feed parsing + validation
│   │   ├── src/
│   │   │   ├── parser.rs          # CSV-based GTFS parser (no gtfs-structures)
│   │   │   ├── validator.rs       # Feed validation and quality reporting
│   │   │   ├── frequency.rs       # Frequency-based trip expansion
│   │   │   ├── calendar.rs        # Service calendar with exceptions
│   │   │   └── error.rs
│   │   └── Cargo.toml             # deps: nx-model, csv, zip, chrono
│   │
│   ├── nx-gtfs-rt/                # GTFS Realtime consumer
│   │   ├── src/
│   │   │   ├── poller.rs          # Async GTFS-RT feed polling
│   │   │   ├── overlay.rs         # RealtimeOverlay (trip updates, alerts)
│   │   │   ├── proto.rs           # Generated from gtfs-realtime.proto
│   │   │   └── error.rs
│   │   └── Cargo.toml             # deps: nx-model, prost, reqwest, arc-swap
│   │
│   ├── nx-graph/                  # Graph construction and storage
│   │   ├── src/
│   │   │   ├── timetable.rs       # CSR-layout transit timetable
│   │   │   ├── builder.rs         # GTFS → Timetable construction
│   │   │   ├── storage.rs         # rkyv serialization + mmap loading
│   │   │   ├── transfers.rs       # Walking transfer generation
│   │   │   ├── spatial.rs         # R-tree spatial index for stops
│   │   │   └── error.rs
│   │   └── Cargo.toml             # deps: nx-model, memmap2, rkyv, rstar
│   │
│   ├── nx-raptor/                 # RAPTOR algorithm (pure, no I/O)
│   │   ├── src/
│   │   │   ├── standard.rs        # Single-criterion RAPTOR (earliest arrival)
│   │   │   ├── range.rs           # Range RAPTOR (time window search)
│   │   │   ├── multicriteria.rs   # McRAPTOR (Pareto-optimal)
│   │   │   ├── pareto.rs          # Pareto set management with dominance pruning
│   │   │   ├── state.rs           # Per-round arrival state arrays
│   │   │   ├── heuristic.rs       # Forward RAPTOR as pruning heuristic
│   │   │   ├── config.rs          # RAPTOR tuning parameters
│   │   │   └── error.rs
│   │   └── Cargo.toml             # deps: nx-model, nx-graph ONLY
│   │
│   ├── nx-router/                 # Orchestration: transit + street + filters
│   │   ├── src/
│   │   │   ├── planner.rs         # Top-level journey planner
│   │   │   ├── street_client.rs   # OSRM client for street routing
│   │   │   ├── access_egress.rs   # First/last mile stop finding
│   │   │   ├── transfer_opt.rs    # Transfer optimization (post-processing)
│   │   │   ├── filter_chain.rs    # Itinerary filters (walk removal, etc.)
│   │   │   ├── car_transit.rs     # CAR_TRANSIT mode logic
│   │   │   ├── fare.rs            # Fare estimation
│   │   │   └── error.rs
│   │   └── Cargo.toml             # deps: nx-raptor, nx-graph, nx-gtfs-rt, reqwest
│   │
│   ├── nx-api/                    # HTTP + GraphQL layer (thin)
│   │   ├── src/
│   │   │   ├── graphql/
│   │   │   │   ├── schema.rs      # async-graphql schema definition
│   │   │   │   ├── plan.rs        # Plan query resolvers
│   │   │   │   ├── stops.rs       # Stop/route query resolvers
│   │   │   │   └── types.rs       # GraphQL type mappings
│   │   │   ├── rest.rs            # Legacy REST compatibility endpoints
│   │   │   ├── health.rs          # Health check, readiness, liveness
│   │   │   └── error.rs           # Domain error → HTTP/GraphQL error mapping
│   │   └── Cargo.toml             # deps: nx-router, axum, async-graphql, tower
│   │
│   ├── nx-config/                 # Configuration
│   │   ├── src/
│   │   │   ├── lib.rs             # CityConfig, RouterConfig, OsrmConfig
│   │   │   └── validation.rs      # Config validation at startup
│   │   └── Cargo.toml             # deps: serde, toml, figment
│   │
│   ├── nx-observe/                # Observability
│   │   ├── src/
│   │   │   ├── metrics.rs         # Prometheus metrics (routing latency, cache hits)
│   │   │   ├── tracing.rs         # OpenTelemetry distributed tracing
│   │   │   └── logging.rs         # Structured JSON logging
│   │   └── Cargo.toml             # deps: tracing, metrics, opentelemetry, tracing-subscriber
│   │
│   └── nx-testkit/                # Shared test infrastructure
│       ├── src/
│       │   ├── fixtures.rs        # Synthetic GTFS feed generators
│       │   ├── assertions.rs      # Custom routing result assertions
│       │   └── proptest_utils.rs  # Property test strategies
│       └── Cargo.toml             # deps: nx-model, proptest
│
├── server/                        # Binary crate
│   ├── src/main.rs                # Startup, config loading, server launch
│   └── Cargo.toml                 # deps: nx-api, nx-config, nx-observe, clap
│
├── tools/
│   ├── gtfs-validate/             # Standalone GTFS validation CLI
│   │   ├── src/main.rs
│   │   └── Cargo.toml
│   ├── graph-build/               # Offline graph builder CLI
│   │   ├── src/main.rs
│   │   └── Cargo.toml
│   └── graph-inspect/             # Graph debugging/inspection CLI
│       ├── src/main.rs
│       └── Cargo.toml
│
├── benches/                       # Criterion benchmarks
│   ├── raptor_bench.rs
│   ├── graph_load_bench.rs
│   └── api_bench.rs
│
├── tests/
│   └── integration/               # End-to-end tests
│       ├── routing_tests.rs
│       ├── api_tests.rs
│       └── fixtures/              # Real GTFS data (small subsets)
│
├── proto/
│   └── gtfs-realtime.proto        # GTFS-RT protobuf definition
│
└── .cargo/
    └── config.toml                # Workspace-wide rustflags, lints
```

### 3.2 Dependency Flow (Strictly Enforced)

```
nx-model          (zero project deps)
    ↑
nx-gtfs           (depends on: nx-model)
nx-gtfs-rt        (depends on: nx-model)
nx-graph          (depends on: nx-model)
    ↑
nx-raptor         (depends on: nx-model, nx-graph)
    ↑
nx-router         (depends on: nx-raptor, nx-graph, nx-gtfs-rt)
    ↑
nx-api            (depends on: nx-router)
    ↑
server            (depends on: nx-api, nx-config, nx-observe)
```

**Rule**: Dependencies flow strictly upward. `nx-raptor` never imports HTTP types. `nx-graph` never imports API types. Enforce in CI.

---

## 4. Core Data Structures

### 4.1 Transit Timetable (CSR Layout)

```rust
// crates/nx-graph/src/timetable.rs

use rkyv::{Archive, Serialize, Deserialize};

/// Newtype indices — zero-cost, prevent mixing up stop/route/trip IDs.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, Archive, Serialize, Deserialize)]
#[repr(transparent)]
pub struct StopIdx(pub u32);

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, Archive, Serialize, Deserialize)]
#[repr(transparent)]
pub struct RouteIdx(pub u32);

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, Archive, Serialize, Deserialize)]
#[repr(transparent)]
pub struct TripIdx(pub u32);

/// Compact stop time — 8 bytes total, fits 8 per cache line.
#[derive(Clone, Copy, Archive, Serialize, Deserialize)]
#[repr(C)]
pub struct PackedStopTime {
    pub arrival: u32,     // seconds since midnight (supports >24h GTFS times)
    pub departure: u32,   // seconds since midnight
}

/// Transfer edge between nearby stops.
#[derive(Clone, Copy, Archive, Serialize, Deserialize)]
#[repr(C)]
pub struct TransferEdge {
    pub to_stop: StopIdx,
    pub walk_seconds: u16,
    _pad: u16,  // alignment
}

/// The core timetable — CSR (Compressed Sparse Row) layout for cache-friendly access.
/// Built once during GTFS import, then memory-mapped for zero-cost loading.
#[derive(Archive, Serialize, Deserialize)]
pub struct Timetable {
    // === Stop metadata ===
    pub num_stops: u32,
    pub stop_names: Vec<String>,
    pub stop_lat: Vec<f64>,
    pub stop_lon: Vec<f64>,
    pub stop_ids: Vec<String>,        // original GTFS stop_id for API responses

    // === Route→Stops: route r has stops at route_stops[route_stop_offsets[r]..route_stop_offsets[r+1]] ===
    pub num_routes: u32,
    pub route_stop_offsets: Vec<u32>,
    pub route_stops: Vec<StopIdx>,

    // === Stop→Routes: stop s is served by stop_routes[stop_route_offsets[s]..stop_route_offsets[s+1]] ===
    pub stop_route_offsets: Vec<u32>,
    pub stop_routes: Vec<RouteIdx>,

    // === Trip stop times: trip t has times at stop_times[trip_offsets[t]..trip_offsets[t+1]] ===
    pub num_trips: u32,
    pub trip_offsets: Vec<u32>,
    pub stop_times: Vec<PackedStopTime>,

    // === Route→Trips: route r has trips route_trips[route_trip_offsets[r]..route_trip_offsets[r+1]] ===
    // Trips within a route are sorted by departure time at first stop (for binary search).
    pub route_trip_offsets: Vec<u32>,
    pub route_trips: Vec<TripIdx>,

    // === Transfers: stop s has transfers at transfers[transfer_offsets[s]..transfer_offsets[s+1]] ===
    pub transfer_offsets: Vec<u32>,
    pub transfers: Vec<TransferEdge>,

    // === Service calendar ===
    pub trip_service: Vec<u32>,       // trip → service_id index
    pub service_dates: Vec<Vec<u32>>, // service_id → sorted list of active dates (days since epoch)

    // === Route metadata ===
    pub route_names: Vec<String>,
    pub route_short_names: Vec<String>,
    pub route_types: Vec<u8>,         // GTFS route_type
}
```

### 4.2 RAPTOR State

```rust
// crates/nx-raptor/src/state.rs

/// Per-round, per-stop arrival state for standard RAPTOR.
pub struct RaptorState {
    /// Best known arrival time at each stop (across all rounds).
    pub best_time: Vec<u32>,          // indexed by StopIdx

    /// Arrival time at each stop in each round.
    /// round_times[round * num_stops + stop] = arrival time
    pub round_times: Vec<u32>,        // flat 2D array for cache locality

    /// Which stops were improved in the current round (for marking).
    pub marked_stops: BitVec,

    /// Journey reconstruction: how we reached each stop.
    pub labels: Vec<Vec<Label>>,      // labels[stop] = path to this stop
}

/// How we reached a stop — for journey reconstruction.
#[derive(Clone)]
pub enum Label {
    /// Boarded a transit vehicle.
    Transit {
        trip: TripIdx,
        boarded_at: StopIdx,
        round: u8,
    },
    /// Walked from a nearby stop.
    Transfer {
        from_stop: StopIdx,
        walk_seconds: u16,
    },
    /// Origin (first/last mile from street network).
    Origin {
        walk_seconds: u16,
    },
}

/// Multi-criteria label for McRAPTOR (Pareto-optimal search).
#[derive(Clone)]
pub struct McLabel {
    pub arrival_time: u32,
    pub num_transfers: u8,
    pub total_duration: u32,
    pub generalized_cost: u32,  // weighted combination for dominance
    pub journey: SmallVec<[Label; 4]>,
}

impl McLabel {
    /// Returns true if `self` dominates `other` (is better or equal on ALL criteria).
    pub fn dominates(&self, other: &McLabel) -> bool {
        self.arrival_time <= other.arrival_time
            && self.num_transfers <= other.num_transfers
            && self.total_duration <= other.total_duration
            && self.generalized_cost <= other.generalized_cost
            && (self.arrival_time < other.arrival_time
                || self.num_transfers < other.num_transfers
                || self.total_duration < other.total_duration
                || self.generalized_cost < other.generalized_cost)
    }
}
```

### 4.3 GTFS-RT Realtime Overlay

```rust
// crates/nx-gtfs-rt/src/overlay.rs

use arc_swap::ArcSwap;
use std::collections::HashMap;

/// Immutable snapshot of realtime state. Swapped atomically via ArcSwap.
pub struct RealtimeOverlay {
    /// Per-trip delay adjustments (trip_id → delays per stop index).
    pub trip_updates: HashMap<TripIdx, TripRealtimeState>,

    /// Active service alerts.
    pub alerts: Vec<ServiceAlert>,

    /// Timestamp when this overlay was created.
    pub updated_at: std::time::Instant,
}

pub struct TripRealtimeState {
    pub delays: Vec<StopDelay>,          // indexed by stop-sequence within trip
    pub schedule_relationship: ScheduleRelationship,
}

pub struct StopDelay {
    pub arrival_delay_secs: i32,         // positive = late, negative = early
    pub departure_delay_secs: i32,
}

pub enum ScheduleRelationship {
    Scheduled,
    Added,
    Canceled,
    Unscheduled,
}

/// Thread-safe realtime state holder. Updated by poller, read by RAPTOR.
pub struct RealtimeState {
    overlay: ArcSwap<RealtimeOverlay>,
}

impl RealtimeState {
    pub fn current(&self) -> arc_swap::Guard<Arc<RealtimeOverlay>> {
        self.overlay.load()
    }

    pub fn update(&self, new_overlay: RealtimeOverlay) {
        self.overlay.store(Arc::new(new_overlay));
    }
}
```

---

## 5. Core Trait Design

### 5.1 Routing Traits (Static Dispatch for Hot Paths)

```rust
// crates/nx-graph/src/lib.rs

/// Transit network access — called millions of times per query.
/// Generic (static dispatch), NOT dyn. Zero overhead.
pub trait TransitNetwork: Send + Sync {
    fn num_stops(&self) -> u32;
    fn num_routes(&self) -> u32;

    /// Routes serving a stop. Returns contiguous slice (CSR).
    fn routes_at_stop(&self, stop: StopIdx) -> &[RouteIdx];

    /// Stops on a route, in order. Returns contiguous slice (CSR).
    fn stops_on_route(&self, route: RouteIdx) -> &[StopIdx];

    /// Trips on a route, sorted by departure at first stop.
    fn trips_on_route(&self, route: RouteIdx) -> &[TripIdx];

    /// Stop times for a trip. Returns contiguous slice.
    fn stop_times(&self, trip: TripIdx) -> &[PackedStopTime];

    /// Walking transfers from a stop. Returns contiguous slice (CSR).
    fn transfers_from(&self, stop: StopIdx) -> &[TransferEdge];

    /// Is this service active on the given date?
    fn is_service_active(&self, trip: TripIdx, date: ServiceDate) -> bool;

    /// Get effective departure time (incorporates realtime delays).
    fn effective_departure(&self, trip: TripIdx, stop_seq: usize) -> u32;

    /// Get effective arrival time (incorporates realtime delays).
    fn effective_arrival(&self, trip: TripIdx, stop_seq: usize) -> u32;
}
```

```rust
// crates/nx-router/src/lib.rs

/// Top-level routing interface. dyn-safe (called once per HTTP request).
pub trait Router: Send + Sync {
    fn plan(&self, request: &RoutingRequest) -> Result<Vec<Itinerary>, RoutingError>;
}

/// Street routing interface — abstracts OSRM client.
pub trait StreetRouter: Send + Sync {
    fn route(
        &self,
        from: Coordinate,
        to: Coordinate,
        mode: StreetMode,
    ) -> Result<Option<StreetPath>, StreetRoutingError>;

    fn nearest_streets(
        &self,
        point: Coordinate,
        radius_meters: f64,
    ) -> Result<Vec<NearestResult>, StreetRoutingError>;
}
```

### 5.2 What NOT to Abstract

- **GtfsParser**: One implementation. No trait needed.
- **GraphBuilder**: One implementation. No trait needed.
- **ConfigLoader**: One implementation. No trait needed.
- **MetricsCollector**: Use the `metrics` crate facade directly.

Premature abstraction is the #1 cause of Rust rewrite failures. Only create traits where you have genuine polymorphism (testing with mocks, swappable backends, multiple algorithms).

---

## 6. Concurrency Model

### 6.1 Architecture: Tokio I/O + Rayon Compute

```
                     ┌────────────────────────────────────┐
  HTTP requests      │      tokio async runtime            │
  ────────────►      │  - Request parsing                  │
                     │  - Response serialization            │
  Health checks      │  - GTFS-RT polling                  │
  ────────────►      │  - Metrics endpoint                 │
                     │  - Graceful shutdown                │
                     └──────────┬─────────────────────────┘
                                │ oneshot channel
                     ┌──────────▼─────────────────────────┐
                     │    rayon thread pool (fixed size)    │
                     │  - RAPTOR computation               │
                     │  - Transfer optimization            │
                     │  - Filter chain                     │
                     │  - Work-stealing for load balance   │
                     │                                     │
                     │  Sizing: num_cores - 4              │
                     │  (leave 4 cores for tokio I/O)      │
                     └─────────────────────────────────────┘
```

### 6.2 Implementation

```rust
// crates/nx-router/src/planner.rs

use tokio::sync::oneshot;

pub struct RoutingService {
    router: Arc<CityRouter>,
    compute_pool: rayon::ThreadPool,
    request_timeout: Duration,
}

impl RoutingService {
    pub fn new(router: Arc<CityRouter>, parallelism: usize) -> Self {
        let compute_pool = rayon::ThreadPoolBuilder::new()
            .num_threads(parallelism)
            .thread_name(|i| format!("raptor-compute-{i}"))
            .build()
            .expect("failed to build compute pool");

        Self {
            router,
            compute_pool,
            request_timeout: Duration::from_secs(5),
        }
    }

    /// Called from async context. Dispatches CPU work to rayon.
    pub async fn route(&self, request: RoutingRequest) -> Result<Vec<Itinerary>, RoutingError> {
        let router = Arc::clone(&self.router);
        let (tx, rx) = oneshot::channel();

        self.compute_pool.spawn(move || {
            let result = router.plan(&request);
            let _ = tx.send(result);
        });

        match tokio::time::timeout(self.request_timeout, rx).await {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err(RoutingError::ComputeWorkerDropped),
            Err(_) => Err(RoutingError::Timeout {
                limit: self.request_timeout,
            }),
        }
    }
}
```

### 6.3 Why NOT spawn_blocking

| Aspect | `spawn_blocking` | rayon |
|--------|-----------------|-------|
| Pool sizing | Dynamic, up to 512 threads | Fixed, you control |
| Work stealing | No | Yes |
| Backpressure | None (spawns more threads) | Natural (fixed pool) |
| Memory under load | Unbounded growth | Predictable |
| Best for | Short blocking I/O | CPU-bound computation |

---

## 7. Error Handling Strategy

### 7.1 Layered Errors (thiserror per crate, no anyhow in libs)

```rust
// nx-model: Lean, zero-dep validation errors
#[derive(Debug, Clone, PartialEq)]
pub enum ValidationError {
    InvalidStopId(String),
    InvalidTime { field: &'static str, value: String },
    MissingRequiredField(&'static str),
}

// nx-gtfs: Parse-level errors
#[derive(Debug, thiserror::Error)]
pub enum GtfsError {
    #[error("CSV error in {file} row {row}: {source}")]
    CsvParse { file: &'static str, row: usize, source: csv::Error },
    #[error("validation: {0:?}")]
    Validation(ValidationError),
    #[error("missing required file: {0}")]
    MissingFile(&'static str),
}

// nx-raptor: Algorithm-level errors
#[derive(Debug, thiserror::Error)]
#[non_exhaustive]
pub enum RaptorError {
    #[error("no route found from stop {from:?} to stop {to:?}")]
    NoRouteFound { from: StopIdx, to: StopIdx },
    #[error("routing timed out after {elapsed:?}")]
    Timeout { elapsed: Duration },
    #[error("stop {0:?} not in graph")]
    StopNotFound(StopIdx),
    #[error("no active service on {0}")]
    NoServiceOnDate(ServiceDate),
}

// nx-api: Maps domain errors to HTTP responses
impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        match &self.0 {
            RoutingError::Raptor(RaptorError::NoRouteFound { .. }) => {
                // 200 with empty results (valid response, not an error)
                Json(PlanResponse { itineraries: vec![] }).into_response()
            }
            RoutingError::Timeout { .. } => {
                StatusCode::SERVICE_UNAVAILABLE.into_response()
            }
            _ => {
                tracing::error!(error = ?self.0, "internal routing error");
                StatusCode::INTERNAL_SERVER_ERROR.into_response()
            }
        }
    }
}
```

### 7.2 Rules

1. **`unwrap()` and `expect()` forbidden** outside tests and `main()`. Enforce: `#![deny(clippy::unwrap_used)]`
2. **`anyhow`** only in binary crates (`server/`, `tools/`). Never in library crates.
3. **"No route found" is a valid result** (`Ok(vec![])`), not an error. `RaptorError::NoRouteFound` means the stops don't exist in the graph.
4. **`#[non_exhaustive]`** on all public error enums (allows adding variants without breaking changes).
5. **Panics are bugs**. All public functions return `Result`. Use `debug_assert!` for invariants.

---

## 8. Configuration System

```rust
// crates/nx-config/src/lib.rs

use serde::Deserialize;

#[derive(Debug, Deserialize)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub city: CityConfig,
    pub routing: RoutingConfig,
    pub osrm: OsrmConfig,
    pub realtime: RealtimeConfig,
    pub observe: ObserveConfig,
}

#[derive(Debug, Deserialize)]
pub struct CityConfig {
    pub id: String,                     // "chennai", "bangalore", etc.
    pub name: String,
    pub timezone: String,               // "Asia/Kolkata"
    pub gtfs_path: PathBuf,             // path to serialized timetable
    pub osm_tag_profile: OsmProfile,    // Bangalore/Chennai speed profiles
}

#[derive(Debug, Deserialize)]
pub struct RoutingConfig {
    pub max_transfers: u8,              // default: 4
    pub max_walk_meters: u32,           // default: 1000
    pub board_slack_secs: HashMap<TransitMode, u32>,  // bus: 300, metro: 120
    pub alight_slack_secs: HashMap<TransitMode, u32>,
    pub walk_speed_mps: f64,            // default: 1.2
    pub max_trip_duration_secs: u32,    // default: 7200
    pub raptor_rounds: u8,              // default: 5 (max transfers + 1)
    pub mcraptor_criteria: Vec<String>, // ["arrival_time", "transfers", "duration"]
}

#[derive(Debug, Deserialize)]
pub struct OsrmConfig {
    pub url: String,                    // "http://osrm-india:5000"
    pub timeout_ms: u64,
    pub retry_count: u8,
}

#[derive(Debug, Deserialize)]
pub struct RealtimeConfig {
    pub enabled: bool,
    pub gtfs_rt_url: Option<String>,
    pub poll_interval_secs: u64,        // default: 30
}
```

**Config loading**: Use `figment` to merge TOML file + environment variables + CLI args. Validate at startup, fail fast on invalid config.

---

## 9. Observability

```rust
// crates/nx-observe/src/metrics.rs

use metrics::{counter, histogram, gauge};

pub fn record_routing_request(city: &str, mode: &str, duration: Duration, success: bool) {
    let labels = [("city", city.to_string()), ("mode", mode.to_string())];
    histogram!("routing_request_duration_seconds", &labels).record(duration.as_secs_f64());
    counter!("routing_requests_total", &labels).increment(1);
    if !success {
        counter!("routing_errors_total", &labels).increment(1);
    }
}

pub fn record_graph_load(city: &str, duration: Duration, num_stops: u32) {
    gauge!("graph_stops_total", &[("city", city)]).set(num_stops as f64);
    histogram!("graph_load_duration_seconds", &[("city", city)]).record(duration.as_secs_f64());
}

pub fn record_realtime_update(city: &str, trip_updates: usize, staleness: Duration) {
    gauge!("realtime_trip_updates", &[("city", city)]).set(trip_updates as f64);
    gauge!("realtime_staleness_seconds", &[("city", city)]).set(staleness.as_secs_f64());
}
```

**Stack**: `tracing` + `tracing-subscriber` for structured logging, `metrics` + `metrics-exporter-prometheus` for Prometheus, `opentelemetry` for distributed tracing. Health endpoints: `/health/live` (process alive), `/health/ready` (graph loaded + OSRM reachable).

---

## 10. Phased Implementation Plan (14-18 Months)

### Phase 0: Foundation + Nandi Audit (Months 1-2)

**Goal**: Project setup + understand what we're actually building.

| Task | Deliverable | Duration |
|------|-------------|----------|
| Audit all 13 NammaYatri OTP commits | Specification document of exact behaviors | 1 week |
| Audit nandi GTFS generators (Python/JS) | Document every transformation, non-standard extension | 1 week |
| Document current GraphQL API contract | OpenAPI/GraphQL schema with examples | 1 week |
| Set up Rust workspace skeleton | All crates created with CI/CD, linting, benchmarks | 1 week |
| Implement `nx-model` | All domain types, newtype indices, serde/rkyv derives | 1 week |
| Implement `nx-gtfs` parser | CSV parser → Timetable builder, handles frequencies.txt | 2 weeks |
| Implement `nx-graph` with rkyv storage | CSR timetable + mmap loading + R-tree spatial index | 2 weeks |
| **Deliverable: GTFS Validator CLI** | `gtfs-validate` tool — immediate value for nandi team | Week 8 |

**Incremental Value**: GTFS validator catches data quality issues before graph build (currently discovered only when OTP fails).

### Phase 1: RAPTOR Core (Months 3-6)

**Goal**: Correct, tested RAPTOR implementation.

| Task | Deliverable | Duration |
|------|-------------|----------|
| RAPTOR design specification | 20-page doc covering all variants, edge cases, Pareto criteria | 2 weeks |
| Standard RAPTOR (earliest arrival) | Single-criterion, single-departure search | 3 weeks |
| Range RAPTOR (time window) | Search over departure window, best per departure minute | 2 weeks |
| Multi-criteria RAPTOR (McRAPTOR) | Pareto-optimal on: arrival time, transfers, duration, cost | 4 weeks |
| Pareto set management | Dominance pruning, label compression, bounded set size | 2 weeks |
| Frequency-based trips | Handle `frequencies.txt` without memory explosion | 1 week |
| Transfer generation | Walking transfers between nearby stops (spatial index) | 1 week |
| Journey reconstruction | Extract human-readable itineraries from RAPTOR labels | 1 week |
| **Validation**: Differential testing vs OTP | 10,000+ query corpus per city, Pareto-aware comparison | Ongoing |

**Incremental Value**: RAPTOR library usable for batch analysis (travel time matrices, coverage reports) even before API is built.

### Phase 2: API + Street Routing Integration (Months 6-9)

**Goal**: Working HTTP API for single city (Chennai pilot).

| Task | Deliverable | Duration |
|------|-------------|----------|
| OSRM deployment for India | OSRM with India OSM extract, city-specific profiles | 1 week |
| `nx-router` orchestration | Combines RAPTOR + OSRM for full journey planning | 2 weeks |
| Access/egress logic | Find nearby stops, walk/car to first transit stop | 2 weeks |
| Transfer optimization | Post-processing: prefer better connections | 2 weeks |
| Filter chain | Walk removal, duration filters, NammaYatri-specific | 1 week |
| CAR_TRANSIT mode | Drive to transit station, park, take transit | 2 weeks |
| GraphQL API | GTFS schema compatible with current OTP endpoints | 2 weeks |
| Observability | Prometheus metrics, structured logging, health checks | 1 week |
| **Deliverable: Chennai pilot (internal)** | Full routing for Chennai, deployed internally | Week 36 |

**Incremental Value**: Chennai routing available for internal testing alongside OTP.

### Phase 3: GTFS-RT + Remaining Features (Months 9-12)

**Goal**: Feature parity with current OTP deployment.

| Task | Deliverable | Duration |
|------|-------------|----------|
| GTFS-RT consumer | Poll feeds, build RealtimeOverlay, ArcSwap updates | 2 weeks |
| Station entrance/exit | Entrance/exit coordinates on legs | 1 week |
| Fare estimation | Distance/zone-based fare calculation | 2 weeks |
| Geocoding / stop search | Fuzzy stop name search (tantivy or similar) | 1 week |
| Accessibility routing | Wheelchair-accessible route filtering | 1 week |
| Service alerts | Surface GTFS-RT alerts through API | 1 week |
| Graph hot-reload | Update GTFS data without restart (double-buffer) | 2 weeks |
| All 7 cities | Deploy + validate for all cities | 2 weeks |
| **Deliverable: All cities running internally** | Full feature parity, all cities | Week 48 |

### Phase 4: Production Migration (Months 12-14)

**Goal**: Safe cutover from OTP to Rust.

| Task | Deliverable | Duration |
|------|-------------|----------|
| Shadow traffic | Route production requests to both OTP + Rust, compare | 3 weeks |
| Discrepancy triage | Classify differences as OTP-bug vs Rust-bug vs valid-difference | 2 weeks |
| Load testing | 2x peak traffic, all cities, sustained 1 hour | 1 week |
| Gradual rollout | 10% → 25% → 50% → 100% traffic per city | 3 weeks |
| Monitoring dashboards | Grafana dashboards for routing metrics | 1 week |
| Runbook + on-call docs | Operational documentation | 1 week |

### Phase 5: Optimization + Hardening (Months 14-18)

**Goal**: Achieve 10x performance targets, harden for scale.

| Task | Deliverable | Duration |
|------|-------------|----------|
| Profile-guided optimization | PGO build, SIMD for distance calculations | 2 weeks |
| Custom CH (optional) | If OSRM latency is bottleneck, bring street routing in-process | 4 weeks |
| Query caching | LRU cache for common origin-destination pairs | 1 week |
| Multi-city consolidation (optional) | If memory allows, serve 2-3 small cities per process | 1 week |
| Security audit | Input validation, fuzzing, dependency audit | 2 weeks |
| Performance regression CI | Criterion benchmarks as CI gates | 1 week |

---

## 11. Testing Strategy

### 11.1 Testing Pyramid

```
                    ┌──────────────────┐
                    │  Shadow Traffic   │  (Phase 4)
                    │  Production parity│
                    └────────┬─────────┘
               ┌─────────────┴──────────────┐
               │  Integration Tests          │  10,000+ queries/city
               │  Differential vs OTP        │  Pareto-aware comparison
               └─────────────┬──────────────┘
          ┌──────────────────┴───────────────────┐
          │  Property-Based Tests (proptest)       │
          │  - Journey temporal consistency         │
          │  - Pareto dominance invariants          │
          │  - Transfer feasibility                 │
          └──────────────────┬───────────────────┘
     ┌───────────────────────┴────────────────────────┐
     │  Unit Tests (per crate)                         │
     │  - GTFS parser edge cases                       │
     │  - CSR graph construction correctness           │
     │  - RAPTOR on synthetic micro-networks           │
     │  - Realtime overlay merge logic                 │
     └───────────────────────┬────────────────────────┘
┌────────────────────────────┴─────────────────────────────┐
│  Fuzz Tests (cargo-fuzz / libfuzzer)                      │
│  - GTFS parser: malformed CSV, encoding issues            │
│  - GTFS-RT: malformed protobuf                           │
│  - API: malformed GraphQL queries                        │
└──────────────────────────────────────────────────────────┘
```

### 11.2 Property-Based Test Examples

```rust
// crates/nx-testkit/src/proptest_utils.rs

use proptest::prelude::*;

prop_compose! {
    fn arb_routing_request(num_stops: u32)
        (from in 0..num_stops, to in 0..num_stops, time in 0u32..86400)
        -> (StopIdx, StopIdx, u32)
    {
        (StopIdx(from), StopIdx(to), time)
    }
}

// In nx-raptor tests:
proptest! {
    #[test]
    fn journey_times_are_monotonic(
        (from, to, dep_time) in arb_routing_request(100)
    ) {
        let graph = test_fixtures::small_network();
        let results = raptor_search(&graph, from, to, dep_time);

        for itinerary in &results {
            for window in itinerary.legs.windows(2) {
                // Each leg starts after the previous one ends
                prop_assert!(window[1].departure >= window[0].arrival,
                    "Non-monotonic: leg ends at {} but next starts at {}",
                    window[0].arrival, window[1].departure);
            }
        }
    }

    #[test]
    fn pareto_optimality_holds(
        (from, to, dep_time) in arb_routing_request(100)
    ) {
        let graph = test_fixtures::small_network();
        let results = mc_raptor_search(&graph, from, to, dep_time);

        // No result should dominate another
        for (i, a) in results.iter().enumerate() {
            for (j, b) in results.iter().enumerate() {
                if i != j {
                    prop_assert!(!a.dominates(b),
                        "Result {} dominates result {}", i, j);
                }
            }
        }
    }
}
```

### 11.3 Benchmark Framework

```rust
// benches/raptor_bench.rs
use criterion::{criterion_group, criterion_main, Criterion, BenchmarkId};

fn raptor_benchmark(c: &mut Criterion) {
    let graph = load_chennai_graph();
    let queries = load_benchmark_queries("chennai", 1000);

    let mut group = c.benchmark_group("raptor");
    group.sample_size(100);

    group.bench_function("standard_raptor", |b| {
        b.iter(|| {
            for q in &queries {
                standard_raptor(&graph, q.from, q.to, q.departure);
            }
        })
    });

    group.bench_function("range_raptor", |b| {
        b.iter(|| {
            for q in &queries {
                range_raptor(&graph, q.from, q.to, q.window_start, q.window_end);
            }
        })
    });

    group.bench_function("mc_raptor", |b| {
        b.iter(|| {
            for q in &queries {
                mc_raptor(&graph, q.from, q.to, q.departure);
            }
        })
    });

    group.finish();
}

criterion_group!(benches, raptor_benchmark);
criterion_main!(benches);
```

CI gate: fail if p50 regresses by >10% or p99 regresses by >20%.

---

## 12. Iterative Development Loops

### Loop 1: Design/Implementation Plan (Maker & Checker)

For each phase, before writing code:

```
┌─────────────┐    Specification Doc    ┌──────────────┐
│   MAKER     │ ──────────────────────► │   CHECKER    │
│             │                         │              │
│ - Write     │    Review Feedback      │ - Review for │
│   design    │ ◄────────────────────── │   correctness│
│   spec      │                         │   + edge     │
│ - Define    │    Revised Spec         │   cases      │
│   interfaces│ ──────────────────────► │ - Check OTP  │
│ - List edge │                         │   parity     │
│   cases     │    Approved ✓           │ - Verify     │
│             │ ◄────────────────────── │   testability│
└─────────────┘                         └──────────────┘
```

**Spec template** (for each module):
1. **Purpose**: What does this module do?
2. **Inputs/Outputs**: Exact types and contracts
3. **Algorithm**: Step-by-step pseudocode
4. **Edge Cases**: Exhaustive list (midnight crossing, empty GTFS, etc.)
5. **OTP Reference**: Which OTP classes does this replace?
6. **Performance Budget**: Max latency/memory for this component
7. **Test Plan**: Unit tests, property tests, integration tests

### Loop 2: Coding (Maker & Reviewer)

```
┌─────────────┐    Pull Request         ┌──────────────┐
│   MAKER     │ ──────────────────────► │   REVIEWER   │
│             │                         │              │
│ - Implement │    Review Comments      │ - Code review│
│   from spec │ ◄────────────────────── │   (style,    │
│ - Write     │                         │   safety,    │
│   tests     │    Revised PR           │   perf)      │
│ - Run       │ ──────────────────────► │ - Run        │
│   benchmarks│                         │   benchmarks │
│             │    Approved ✓           │ - Check spec │
│             │ ◄────────────────────── │   compliance │
└─────────────┘                         └──────────────┘
```

**Code review checklist**:
- [ ] No `unwrap()` in library code
- [ ] All public types have doc comments
- [ ] Error types are `#[non_exhaustive]`
- [ ] Hot paths use contiguous memory (no `HashMap` in RAPTOR inner loop)
- [ ] Benchmarks included for performance-critical code
- [ ] Property tests for algorithmic invariants
- [ ] No unnecessary allocations (check with `dhat` or `heaptrack`)

### Loop 3: Automated Testing (Creator & Tester)

```
┌─────────────┐    Test Suite           ┌──────────────┐
│   CREATOR   │ ──────────────────────► │   TESTER     │
│             │                         │              │
│ - Write     │    Test Results +       │ - Run all    │
│   unit tests│    Coverage Report      │   tests      │
│ - Write     │ ◄────────────────────── │ - Run fuzz   │
│   property  │                         │   tests      │
│   tests     │    Gap Analysis         │ - Run        │
│ - Write     │ ──────────────────────► │   benchmarks │
│   fuzz      │                         │ - Coverage   │
│   targets   │    Approved ✓           │   analysis   │
│             │ ◄────────────────────── │ - Regression │
└─────────────┘                         │   check      │
                                        └──────────────┘
```

**Test quality gates** (CI enforced):
- Unit test coverage: >80% for algorithm crates (`nx-raptor`, `nx-graph`)
- All property tests pass with 10,000 iterations
- Fuzz tests run for 5 minutes without panics
- Benchmarks show no regression from main branch
- Clippy clean with `#![deny(clippy::all, clippy::pedantic)]`

---

## 13. Repo Strategy and Order

### 13.1 Two-Repo Approach

| Repo | Contents | Purpose |
|------|----------|---------|
| **nammayatri/nandi** | Deployment configs, GTFS data, Python/JS generators, `IMPLEMENTATION_STRATEGY.md` | Operations + data pipeline |
| **nammayatri/OpenTripPlanner** | Current OTP fork (maintained as fallback during migration) | Legacy routing engine |
| **NEW: nammayatri/namma-transit** | Rust workspace (all `nx-*` crates) | New routing engine |

### 13.2 Implementation Order

**Start in nandi repo** (Weeks 1-2):
1. Audit and document nandi GTFS pipeline
2. Create `IMPLEMENTATION_STRATEGY.md` (this document)
3. Document current API contract (GraphQL schema export)

**Create namma-transit repo** (Week 2):
1. Initialize Rust workspace
2. Set up CI/CD (GitHub Actions)
3. Begin `nx-model` + `nx-gtfs` + `nx-graph`

**Maintain OpenTripPlanner fork** (ongoing):
1. Keep running in production until full migration
2. Apply Option D optimizations as bridge (JVM tuning, right-size heap per city)
3. Use as reference and differential testing oracle

### 13.3 Bridge Strategy (Do Immediately)

While Rust rewrite progresses, apply these to current OTP to reduce costs:

| Optimization | Effort | Savings |
|-------------|--------|---------|
| Right-size JVM heap per city (Kochi: 2GB, Mumbai: 8GB) | 1 day | ~30% memory |
| Switch to ZGC garbage collector | 1 day | Lower p99 latency |
| Pod autoscaling (scale down off-peak) | 1 week | ~20% cost |
| Consolidate small cities (Kochi + Bhubaneswar share pod) | 2 days | 2-3 fewer pods |

**Expected: 80 pods → ~40-50 pods within 2 weeks.**

---

## 14. Performance Targets

| Metric | Current (OTP) | Target (Rust) | Validation Method |
|--------|--------------|---------------|-------------------|
| p50 routing latency | 500-2000ms | <100ms | Criterion benchmarks + production metrics |
| p99 routing latency | 3-10s | <500ms | Load test at 2x peak |
| Memory per city | 17-18 GB | 200MB-1GB | `heaptrack` profiling |
| Total cluster memory | ~1,360 GB | <20 GB | Kubernetes monitoring |
| Total pods | 80 | 15-20 | Deployment config |
| Graph build time | 15-30 min | 1-3 min | CI timing |
| Docker image size | 2-5 GB | <50 MB | Docker build |
| Cold start time | 2-5 min | <5s (mmap) | Startup benchmark |
| Concurrent requests/pod | ~50-100 | ~500-1000 | Load test |

---

## 15. Risk Mitigation

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| RAPTOR correctness bugs | High | Critical | 3-layer testing: unit + property + differential. Shadow traffic. |
| Timeline overrun | High | High | Incremental value at each phase. OTP runs as fallback. |
| Rust hiring difficulty | Medium | High | Start with 2-3 senior devs. Use this as training project. |
| GTFS data quality issues | High | Medium | Keep existing Python generators unchanged. Validate in new parser. |
| OSRM integration latency | Medium | Medium | Profile early. Have fallback plan for in-process CH (Phase 5). |
| rkyv format instability | Low | High | Pin rkyv version. Include format version in serialized data. |
| Feature parity gaps discovered late | Medium | High | Shadow traffic comparison catches gaps before cutover. |
| OTP upstream security patches needed | Low | Medium | Maintain OTP fork until full migration. |

---

## 16. Technology Decisions Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| HTTP framework | `axum` + `tokio` | Industry standard, Tower middleware ecosystem |
| GraphQL | `async-graphql` | Schema-first, good OTP schema compatibility |
| GTFS parsing | Custom (not `gtfs-structures`) | Need direct-to-rkyv serialization, frequency handling |
| GTFS-RT | `prost` + GTFS-RT proto | Standard protobuf, well-maintained |
| Serialization | `rkyv` (static) + `serde` (config) | Zero-copy for graph, ergonomic for config |
| Memory mapping | `memmap2` | Standard mmap wrapper, battle-tested |
| Spatial index | `rstar` | R-tree for stop proximity queries |
| Street routing | OSRM (external sidecar) | Battle-tested, not our differentiator |
| Compute pool | `rayon` | Work-stealing, fixed-size, backpressure |
| Realtime sync | `arc-swap` | Wait-free atomic pointer swap |
| Metrics | `metrics` + prometheus exporter | Lightweight, compatible with Grafana |
| Tracing | `tracing` + `opentelemetry` | Structured logging + distributed traces |
| Configuration | `figment` + TOML | Layered config (file + env + CLI) |
| Testing | `proptest` + `criterion` + `cargo-fuzz` | Property tests + benchmarks + fuzz |
| Error handling | `thiserror` (libs), `anyhow` (bins) | Typed errors at boundaries, ergonomic in main |
| Linting | `clippy::pedantic` + `deny(unsafe)` | Maximum safety |

---

## 17. Key Dependencies (Cargo.toml)

```toml
# Workspace-level dependencies (version pinning)
[workspace.dependencies]
# Web
axum = "0.8"
tokio = { version = "1", features = ["full"] }
tower = "0.5"
tower-http = { version = "0.6", features = ["cors", "trace", "timeout"] }
async-graphql = "7"
async-graphql-axum = "7"
reqwest = { version = "0.12", features = ["json"] }

# Serialization
serde = { version = "1", features = ["derive"] }
serde_json = "1"
rkyv = { version = "0.8", features = ["validation"] }
prost = "0.13"
prost-build = "0.13"

# Data
csv = "1.3"
zip = "2"
chrono = { version = "0.4", features = ["serde"] }

# Memory + Concurrency
memmap2 = "0.9"
arc-swap = "1"
rayon = "1.10"
dashmap = "6"
smallvec = { version = "1", features = ["serde"] }

# Geo
geo = "0.29"
rstar = "0.12"

# Observability
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["json", "env-filter"] }
metrics = "0.24"
metrics-exporter-prometheus = "0.16"
opentelemetry = "0.27"

# Config
figment = { version = "0.10", features = ["toml", "env"] }
toml = "0.8"
clap = { version = "4", features = ["derive"] }

# Error handling
thiserror = "2"
anyhow = "1"

# Testing
proptest = "1"
criterion = { version = "0.5", features = ["html_reports"] }
```

---

## 18. CI/CD Pipeline

```yaml
# .github/workflows/ci.yml (conceptual)
name: CI

on: [push, pull_request]

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - run: cargo clippy --workspace --all-targets -- -D warnings
      - run: cargo fmt --check
      - run: cargo deny check  # license + dependency audit

  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - run: cargo test --workspace
      - run: cargo test --workspace -- --ignored  # slow/integration tests
      # Property tests with more iterations in CI
      - run: PROPTEST_CASES=10000 cargo test --workspace -p nx-raptor

  bench:
    runs-on: ubuntu-latest
    if: github.event_name == 'pull_request'
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - run: cargo bench --workspace -- --output-format bencher
      # Compare against main branch, fail on >10% regression

  fuzz:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@nightly
      - run: cargo install cargo-fuzz
      - run: cargo fuzz run gtfs_parser -- -max_total_time=300
      - run: cargo fuzz run gtfs_rt_parser -- -max_total_time=300
```

---

## 19. Glossary

| Term | Definition |
|------|-----------|
| **RAPTOR** | Round-bAsed Public Transit Optimized Router — transit routing algorithm |
| **McRAPTOR** | Multi-criteria RAPTOR — finds Pareto-optimal journeys |
| **Range RAPTOR** | RAPTOR searching over a departure time window |
| **CSR** | Compressed Sparse Row — cache-friendly graph storage format |
| **CH** | Contraction Hierarchies — fast street routing preprocessing |
| **GTFS** | General Transit Feed Specification — standard transit data format |
| **GTFS-RT** | GTFS Realtime — real-time transit updates (delays, cancellations) |
| **OSRM** | Open Source Routing Machine — C++ street routing engine |
| **mmap** | Memory-mapped file — OS maps file directly into process address space |
| **rkyv** | Rust zero-copy deserialization framework |
| **ArcSwap** | Atomic reference-counted pointer swap — wait-free concurrent updates |
| **Pareto optimal** | No solution is better on ALL criteria simultaneously |

---

*This strategy was reviewed independently by two architecture reviewers. Key corrections: timeline extended to 14-18 months, OSRM replaces custom CH, per-city isolation replaces single-process, GTFS-RT added as first-class requirement, rayon compute pool replaces spawn_blocking.*
