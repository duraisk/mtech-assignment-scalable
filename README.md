# Spring Boot Microservices — Docker & Kubernetes Scaling POC

> **SEZG583 Scalable Services** | Assignment: Microservices Development and Deployment

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)
![Java](https://img.shields.io/badge/Java-17-orange)
![Docker](https://img.shields.io/badge/Docker-Desktop-blue)
![Kubernetes](https://img.shields.io/badge/Kubernetes-local-326CE5)

| Field | Value |
|---|---|
| Version | 1.0.0 |
| Date | May 2026 |
| Stack | Spring Boot 3.2 \| Docker \| Kubernetes |
| Environment | Docker Desktop + K8s (local) |

---

## Table of Contents

1. [Overview & Architecture](#1-overview--architecture)
2. [Prerequisites](#2-prerequisites)
3. [Building Docker Images](#3-building-docker-images)
4. [Deploying to Kubernetes](#4-deploying-to-kubernetes)
5. [Scaling Demonstrations](#5-scaling-demonstrations)
6. [API Reference](#6-api-reference)
7. [Useful kubectl Commands](#7-useful-kubectl-commands)
8. [Troubleshooting](#8-troubleshooting)
9. [Quick Reference Cheat Sheet](#9-quick-reference-cheat-sheet)

---

## 1. Overview & Architecture

This POC demonstrates how to containerize Spring Boot microservices with Docker and deploy them to Kubernetes with both manual and automatic (HPA) scaling. The setup runs entirely on Docker Desktop's built-in Kubernetes cluster.

### 1.1 Architecture

Two Spring Boot microservices communicate internally via Kubernetes DNS service discovery:

| user-service | order-service |
|---|---|
| Port `8081` (NodePort: `30081`) | Port `8082` (NodePort: `30082`) |
| REST API: `/api/users` | REST API: `/api/orders` |
| Replicas: 2 to 10 (HPA) | Replicas: 2 to 10 (HPA) |

> Both services run in Kubernetes namespace: `spring-poc` on Docker Desktop Kubernetes.

### 1.2 Key Features

- Multi-stage Docker builds for optimized, slim container images
- Spring Boot Actuator health probes (liveness, readiness, startup)
- Kubernetes HPA (Horizontal Pod Autoscaler) based on CPU and memory metrics
- Manual replica scaling with `kubectl` commands
- Inter-service communication via Kubernetes DNS service discovery
- Docker Compose for rapid local development and testing
- CPU load simulation endpoint for triggering and demonstrating auto-scaling

### 1.3 Project Structure

```
spring-k8s-poc/
  user-service/               # User management microservice
    src/main/java/...         # Java source files
    src/main/resources/       # application.properties
    Dockerfile                # Multi-stage Docker build
    pom.xml                   # Maven build descriptor
  order-service/              # Order management microservice
    src/main/java/...         # Java source files
    src/main/resources/       # application.properties
    Dockerfile
    pom.xml
  k8s/                        # Kubernetes manifests
    namespace.yaml            # spring-poc namespace
    metrics-server-patch.yaml
    user-service/
      deployment.yaml         # Deployment (replicas, probes, resources)
      service.yaml            # NodePort service
      hpa.yaml                # HorizontalPodAutoscaler
    order-service/
      deployment.yaml
      service.yaml
      hpa.yaml
  docker-compose.yml          # Local dev with Docker Compose
  load-test.sh                # Shell script to trigger HPA scaling
```

---

## 2. Prerequisites

> Before starting, ensure all tools listed below are installed and running on your machine.

| Tool / Software | Version / Notes |
|---|---|
| Docker Desktop | 4.x or later — must be running |
| Kubernetes (in Docker Desktop) | Enable in Docker Desktop Settings → Kubernetes |
| kubectl | Bundled with Docker Desktop — verify: `kubectl version` |
| Java JDK | Version 17 or later — verify: `java -version` |
| Apache Maven | 3.8+ — verify: `mvn -version` |
| Git | Any recent version — verify: `git --version` |
| curl or Postman | For API testing |
| bash (Git Bash on Windows) | For running `load-test.sh` |

### 2.1 Enable Kubernetes in Docker Desktop

1. Open Docker Desktop
2. Click the gear icon (Settings) in the top right
3. Navigate to the **Kubernetes** tab on the left sidebar
4. Check **Enable Kubernetes** and click **Apply & Restart**
5. Wait for the green Kubernetes status indicator at the bottom left
6. Verify with `kubectl cluster-info`

```bash
# See all available contexts
kubectl config get-contexts

# Switch to Docker Desktop context
kubectl config use-context docker-desktop

# Verify cluster is reachable
kubectl cluster-info
kubectl get nodes

# Expected output:
# NAME             STATUS   ROLES           AGE   VERSION
# docker-desktop   Ready    control-plane   Xd    v1.xx.x
```

---

## 3. Building Docker Images

Each service uses a two-stage Dockerfile. Stage 1 builds the JAR with Maven; Stage 2 creates a slim runtime image — keeping production images small and secure.

### 3.1 Build user-service

```bash
cd spring-k8s-poc/user-service
docker build -t poc/user-service:latest .
docker images | grep poc/user-service
```

### 3.2 Build order-service

```bash
cd ../order-service
docker build -t poc/order-service:latest .
docker images | grep poc
```

### 3.3 Test Locally with Docker Compose

Before deploying to Kubernetes, validate both services work together:

```bash
# From the spring-k8s-poc root directory
cd ..

# Build and start all services
docker compose up --build

# Or run in detached (background) mode
docker compose up --build -d

# Check running containers
docker compose ps

# View logs
docker compose logs -f
```

Once running, test the APIs:

```bash
# user-service
curl http://localhost:8081/actuator/health
curl http://localhost:8081/api/users

# order-service
curl http://localhost:8082/actuator/health
curl http://localhost:8082/api/orders

# Inter-service call (order with user details)
curl http://localhost:8082/api/orders/1/with-user

# Stop when done
docker compose down
```

---

## 4. Deploying to Kubernetes

### 4.1 Install Metrics Server (Required for HPA)

The Metrics Server collects CPU and memory usage data that HPA needs. Docker Desktop's K8s does not include it by default.

```bash
# Step 1: Install Metrics Server
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

# Step 2: Patch for Docker Desktop
# (Docker Desktop uses self-signed TLS certs that Metrics Server rejects by default)
kubectl patch deployment metrics-server -n kube-system --type='json' -p='[
  {
    "op": "add",
    "path": "/spec/template/spec/containers/0/args/-",
    "value": "--kubelet-insecure-tls"
  }
]'

# Step 3: Wait for rollout, then verify
kubectl rollout status deployment/metrics-server -n kube-system
kubectl get deployment metrics-server -n kube-system
kubectl top nodes   # should show CPU/memory usage
```

> **Important:** Wait 60–90 seconds after patching for the Metrics Server to collect initial metrics. HPA will show `<unknown>` until metrics are available.

### 4.2 Create Namespace

```bash
kubectl apply -f k8s/namespace.yaml
kubectl get namespaces | grep spring-poc
```

### 4.3 Deploy user-service

```bash
kubectl apply -f k8s/user-service/deployment.yaml
kubectl apply -f k8s/user-service/service.yaml
kubectl apply -f k8s/user-service/hpa.yaml

# Verify
kubectl get deployment user-service -n spring-poc
kubectl get pods -n spring-poc -l app=user-service
kubectl get svc user-service -n spring-poc
kubectl get hpa user-service-hpa -n spring-poc
```

### 4.4 Deploy order-service

```bash
kubectl apply -f k8s/order-service/deployment.yaml
kubectl apply -f k8s/order-service/service.yaml
kubectl apply -f k8s/order-service/hpa.yaml

# Verify
kubectl get deployment order-service -n spring-poc
kubectl get pods -n spring-poc -l app=order-service
```

### 4.5 Verify Everything is Running

```bash
kubectl get all -n spring-poc

# Expected output:
# NAME                                READY   STATUS    RESTARTS   AGE
# pod/user-service-xxxx-xxxxx         1/1     Running   0          2m
# pod/user-service-xxxx-xxxxx         1/1     Running   0          2m
# pod/order-service-xxxx-xxxxx        1/1     Running   0          2m
# pod/order-service-xxxx-xxxxx        1/1     Running   0          2m
#
# NAME                TYPE       CLUSTER-IP    PORT(S)          AGE
# svc/user-service    NodePort   10.x.x.x      8081:30081/TCP   2m
# svc/order-service   NodePort   10.x.x.x      8082:30082/TCP   2m
#
# NAME                          REFERENCE              TARGETS      MINPODS   MAXPODS
# hpa/user-service-hpa          Deployment/user-service  cpu: 5%/50%  2         10
# hpa/order-service-hpa         Deployment/order-service cpu: 4%/50%  2         10
```

Test the deployed services:

```bash
curl http://localhost:30081/api/users
curl http://localhost:30081/api/users/health
curl http://localhost:30082/api/orders
curl http://localhost:30082/api/orders/1/with-user
```

---

## 5. Scaling Demonstrations

### 5.1 Manual Replica Scaling

```bash
# Scale up
kubectl scale deployment user-service --replicas=5 -n spring-poc
kubectl scale deployment order-service --replicas=4 -n spring-poc

# Watch pods spin up
kubectl get pods -n spring-poc -w

# Scale down
kubectl scale deployment user-service --replicas=2 -n spring-poc
kubectl scale deployment order-service --replicas=2 -n spring-poc
```

> **Note:** Manual scaling overrides HPA temporarily. HPA reclaims control after its next reconciliation cycle (typically 15–30 seconds).

### 5.2 Automatic Scaling with HPA

#### Monitor HPA in Real-Time

```bash
# Terminal 1 — watch HPA
kubectl get hpa -n spring-poc -w

# Terminal 2 — watch pods
kubectl get pods -n spring-poc -w

# View detailed HPA info
kubectl describe hpa user-service-hpa -n spring-poc
```

#### Trigger CPU Load

```bash
# Option 1: load-test.sh script
chmod +x load-test.sh
./load-test.sh both 500

# Option 2: manual curl loop (Ctrl+C to stop)
while true; do
  curl -s 'http://localhost:30081/api/users/simulate-load?iterations=500' &
  curl -s 'http://localhost:30082/api/orders/simulate-load?iterations=500' &
  sleep 1
done
```

#### Observe Scaling Events

```bash
kubectl describe hpa user-service-hpa -n spring-poc
kubectl get events -n spring-poc --sort-by='.lastTimestamp'
kubectl top pods -n spring-poc
```

### 5.3 HPA Configuration Summary

| HPA Parameter | Value & Description |
|---|---|
| `minReplicas` | 2 — Always maintain 2 pods for high availability |
| `maxReplicas` | 10 — Maximum pods HPA can create |
| CPU Target | 50% — Scale up when average CPU exceeds 50% of request |
| Memory Target | 70% — Scale up when average memory exceeds 70% of request |
| Scale-Up Window | 30s — Stabilization window before another scale-up |
| Scale-Down Window | 120s — Wait 2 min before scaling down (prevents thrashing) |
| Scale-Up Policy | Max +2 pods per 60 seconds |
| Scale-Down Policy | Max −1 pod per 60 seconds |

---

## 6. API Reference

### 6.1 user-service — `http://localhost:30081`

| Endpoint | Description |
|---|---|
| `GET /api/users` | Get all users |
| `GET /api/users/{id}` | Get user by ID |
| `POST /api/users` | Create a new user (JSON body) |
| `PUT /api/users/{id}` | Update user by ID |
| `DELETE /api/users/{id}` | Delete user by ID |
| `GET /api/users/health` | Service health + pod name |
| `GET /api/users/simulate-load` | Trigger CPU load (`?iterations=1000`) |
| `GET /actuator/health` | Spring Boot Actuator health |
| `GET /actuator/metrics` | Application metrics |

### 6.2 order-service — `http://localhost:30082`

| Endpoint | Description |
|---|---|
| `GET /api/orders` | Get all orders |
| `GET /api/orders/{id}` | Get order by ID |
| `GET /api/orders/user/{userId}` | Get orders by user ID |
| `POST /api/orders` | Create a new order (JSON body) |
| `PUT /api/orders/{id}/status` | Update order status (`?status=SHIPPED`) |
| `GET /api/orders/{id}/with-user` | Get order + user details (calls user-service) |
| `GET /api/orders/health` | Service health + pod name |
| `GET /api/orders/simulate-load` | Trigger CPU load (`?iterations=1000`) |
| `GET /actuator/health` | Spring Boot Actuator health |

### 6.3 Sample Payloads

**Create User** — `POST /api/users`
```json
{
  "name": "Durai Kumar",
  "email": "durai@valignit.com",
  "role": "ADMIN"
}
```

**Create Order** — `POST /api/orders`
```json
{
  "userId": 1,
  "product": "MacBook Pro",
  "quantity": 1,
  "totalPrice": 2499.99
}
```

---

## 7. Useful kubectl Commands

### 7.1 Deployment Management

```bash
kubectl get all -n spring-poc
kubectl describe deployment user-service -n spring-poc
kubectl logs -l app=user-service -n spring-poc --tail=50
kubectl logs -f <pod-name> -n spring-poc
kubectl describe pod <pod-name> -n spring-poc
kubectl exec -it <pod-name> -n spring-poc -- /bin/sh
```

### 7.2 Scaling Commands

```bash
kubectl scale deployment user-service --replicas=5 -n spring-poc
kubectl scale deployment order-service --replicas=3 -n spring-poc
kubectl get hpa -n spring-poc -w
kubectl describe hpa user-service-hpa -n spring-poc
kubectl top pods -n spring-poc
kubectl top nodes
```

### 7.3 Updating Deployments

```bash
# Rolling update with new image
kubectl set image deployment/user-service \
  user-service=poc/user-service:v2 -n spring-poc

kubectl rollout status deployment/user-service -n spring-poc
kubectl rollout undo deployment/user-service -n spring-poc
kubectl rollout history deployment/user-service -n spring-poc
```

### 7.4 Cleanup

```bash
# Delete everything in the namespace
kubectl delete namespace spring-poc

# Or delete individually
kubectl delete -f k8s/user-service/
kubectl delete -f k8s/order-service/
kubectl delete -f k8s/namespace.yaml

# Stop Docker Compose
docker compose down

# Remove images
docker rmi poc/user-service:latest poc/order-service:latest
```

---

## 8. Troubleshooting

| Issue | Solution |
|---|---|
| `ImagePullBackOff` | Ensure `imagePullPolicy: Never` in `deployment.yaml` and image was built locally with `docker build -t poc/...` |
| `HPA shows <unknown>` | Install and patch Metrics Server (Section 4.1). Wait 60–90 seconds. |
| `CrashLoopBackOff` | Check logs: `kubectl logs <pod> -n spring-poc` |
| `kubectl: connection refused` | Enable Kubernetes in Docker Desktop → Settings → Kubernetes. Restart Docker Desktop. |
| Service not accessible | Use NodePort (`30081`/`30082`). Check: `kubectl get svc -n spring-poc` |
| `order-service` can't reach `user-service` | Verify `user-service` pod is `Running`. K8s URL: `http://user-service:8081` |
| HPA not scaling | Confirm `resources.requests.cpu` is set in `deployment.yaml` — HPA requires it to calculate utilization % |
| `Unable to connect: gcloud error` | Wrong kubectl context. Run: `kubectl config use-context docker-desktop` |

### Diagnostic Commands

```bash
kubectl describe pod <pod-name> -n spring-poc
kubectl describe nodes | grep -A 5 'Allocated resources'
kubectl top pods -n spring-poc
kubectl top nodes
kubectl get endpoints -n spring-poc
kubectl run -it --rm debug --image=busybox -n spring-poc -- nslookup user-service
```

---

## 9. Quick Reference Cheat Sheet

| Task | Command |
|---|---|
| Build images | `docker build -t poc/user-service:latest user-service/` |
| Start with Compose | `docker compose up --build -d` |
| Deploy to K8s | `kubectl apply -f k8s/ -R` |
| Watch pods | `kubectl get pods -n spring-poc -w` |
| Watch HPA | `kubectl get hpa -n spring-poc -w` |
| Scale manually | `kubectl scale deploy/user-service --replicas=5 -n spring-poc` |
| View logs | `kubectl logs -l app=user-service -n spring-poc -f` |
| CPU metrics | `kubectl top pods -n spring-poc` |
| Trigger load test | `./load-test.sh both 500` |
| Cleanup | `kubectl delete namespace spring-poc` |

---

> **Service Endpoints**
> - user-service: http://localhost:30081/api/users
> - order-service: http://localhost:30082/api/orders
> - Actuator health: http://localhost:30081/actuator/health
