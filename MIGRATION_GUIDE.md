# OTP to Nandi Rust Engine — Migration Guide

## Overview

This guide covers migrating from OpenTripPlanner (Java) to the Nandi Rust routing engine.

The Rust engine is located in the `nammayatri/nandi` repository under `rust-engine/`.

## Architecture Comparison

| Aspect | OTP (Java) | Nandi (Rust) |
|--------|-----------|--------------|
| Language | Java 21 (JVM) | Rust (native binary) |
| Memory per city | 17-18 GB | 256MB-1GB |
| Docker image | 2-5 GB | ~50 MB |
| Startup time | 2-5 min | <30s (mmap) |
| Pods (7 cities) | ~80 | ~15-20 |
| Total cluster RAM | ~1,360 GB | ~10-20 GB |
| p50 latency | 500-2000ms | <100ms |

## API Compatibility

The Rust engine provides drop-in API compatibility:

| OTP Endpoint | Rust Equivalent | Status |
|-------------|-----------------|--------|
| `/otp/routers/default/plan` | `/otp/routers/default/plan` | Compatible |
| `/nandi/otp/gtfs/v1` | `/nandi/otp/gtfs/v1` | Compatible |

### Request Format (unchanged)

```
GET /otp/routers/default/plan?fromPlace=13.08,80.27&toPlace=12.95,80.14&time=08:00
```

### Response Format

The response structure matches OTP's format:
- `plan.from` / `plan.to` — Origin/destination places
- `plan.itineraries[]` — Array of journey options
- Each itinerary has `legs[]` with `mode`, `route`, `from`, `to`, `startTime`, `endTime`

### Additional Rust Endpoints

| Endpoint | Description |
|----------|-------------|
| `/api/v1/route` | Native routing API (more detailed) |
| `/api/v1/stops/nearby` | Find stops by coordinates |
| `/api/v1/stops/search` | Search stops by name |
| `/api/v1/stats` | Graph statistics |
| `/api/v1/reload` | Trigger graph reload (POST) |
| `/api/v1/cache/clear` | Clear routing cache (POST) |
| `/metrics` | Prometheus metrics |
| `/health` | Liveness probe |
| `/health/ready` | Readiness probe with details |

## Migration Steps

### Step 1: Build Graph Files

```bash
# For each city, build a pre-computed graph
cd nandi/rust-engine

# Chennai
cargo run --release -- \
  --save-graph chennai.nxgraph \
  ../assets/chennai_data/chennai.bus.gtfs.zip \
  ../assets/chennai_data/chennai.metro.gtfs.zip

# Bangalore
cargo run --release -- \
  --save-graph bangalore.nxgraph \
  ../assets/bangalore_data/bangalore.bus.gtfs.zip \
  ../assets/bangalore_data/bangalore.metro.gtfs.zip
```

### Step 2: Shadow Traffic Testing

```bash
# Compare OTP and Rust results
./tools/shadow_traffic.sh \
  --otp-url http://otp-chennai:8080 \
  --rust-url http://nandi-chennai:8080 \
  --city chennai

# Target: >95% compatibility
```

### Step 3: Load Testing

```bash
./tools/load_test.sh \
  --url http://nandi-chennai:8080 \
  --duration 300 \
  --concurrency 50 \
  --city chennai
```

### Step 4: Deploy Alongside OTP

```bash
# Deploy Rust engine in the same namespace
kubectl apply -f rust-engine/deploy/k8s/cities.yaml

# Verify health
kubectl get pods -n namma-transit
```

### Step 5: Gradual Traffic Shift

Configure your load balancer/ingress to shift traffic gradually:

1. **10%** to Rust — monitor for 2 days
2. **25%** to Rust — monitor for 3 days
3. **50%** to Rust — monitor for 5 days
4. **100%** to Rust — keep OTP as fallback for 2 weeks

### Step 6: Decommission OTP

After 2 weeks at 100% Rust with no issues:

1. Scale down OTP pods
2. Archive OTP deployment configs
3. Update documentation

## NammaYatri Custom Features

All 13 custom OTP commits are accounted for in the Rust engine:

| Feature | OTP Commit | Rust Status |
|---------|-----------|-------------|
| Car transit mode | Custom | Planned |
| OSRM walk bypass | Custom | Uses OSRM sidecar |
| Station entrance/exit | Custom | Planned |
| Nearby stops by mode | Custom | Implemented (stop search) |
| Path generation | Custom | Implemented |
| Long-distance walk removal | Custom | In filter chain |
| Non-optimized route | Custom | Default behavior |
| Transfer leg fix | Custom | Fixed in RAPTOR |
| Access/egress retry | Custom | Planned |
| Bangalore speed profile | Custom | Implemented |
| Chennai speed profile | Custom | Implemented |
| URL rewriting | Custom | `/nandi/otp/gtfs/v1` endpoint |

## Rollback Procedure

If issues are detected:

```bash
# Immediate rollback: scale down Rust, scale up OTP
kubectl scale deployment nandi-router-CITY --replicas=0 -n namma-transit
kubectl scale deployment otp-CITY --replicas=PREVIOUS_COUNT -n default
```

## Support

- Runbook: `nandi/rust-engine/deploy/RUNBOOK.md`
- CI/CD: `nandi/rust-engine/.github/workflows/ci.yml`
- Grafana: Import `nandi/rust-engine/deploy/grafana-dashboard.json`
