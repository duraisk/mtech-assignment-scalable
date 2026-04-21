#!/bin/bash
# ============================================================
# Load Test Script for HPA Demo
# Sends repeated requests to trigger CPU-based auto-scaling
#
# Usage:
#   chmod +x load-test.sh
#   ./load-test.sh user-service    # Test user-service
#   ./load-test.sh order-service   # Test order-service
#   ./load-test.sh both            # Test both services
# ============================================================

SERVICE=${1:-both}
ITERATIONS=${2:-500}

USER_URL="http://localhost:30081"
ORDER_URL="http://localhost:30082"

echo "============================================"
echo " Spring Boot K8s Scaling POC - Load Test"
echo "============================================"
echo "Service: $SERVICE | Iterations: $ITERATIONS"
echo ""

# Watch HPA status in background
watch_hpa() {
    echo "Watching HPA status (Ctrl+C to stop)..."
    while true; do
        kubectl get hpa -n spring-poc 2>/dev/null
        kubectl get pods -n spring-poc 2>/dev/null
        echo "---"
        sleep 10
    done
}

send_load() {
    local url=$1
    local service=$2
    echo "Sending load to $service at $url..."
    for i in $(seq 1 $ITERATIONS); do
        curl -s "$url/api/${service}s/simulate-load?iterations=500" > /dev/null &
        if (( i % 20 == 0 )); then
            echo "  Sent $i/$ITERATIONS requests to $service"
            wait  # Wait for background jobs to avoid overloading
        fi
    done
    wait
    echo "Load test for $service complete!"
}

echo "Starting load test. Monitor scaling with:"
echo "  kubectl get hpa -n spring-poc -w"
echo "  kubectl get pods -n spring-poc -w"
echo ""

case $SERVICE in
    user-service)
        send_load "$USER_URL" "user"
        ;;
    order-service)
        send_load "$ORDER_URL" "order"
        ;;
    both)
        send_load "$USER_URL" "user" &
        send_load "$ORDER_URL" "order" &
        wait
        ;;
    *)
        echo "Usage: ./load-test.sh [user-service|order-service|both] [iterations]"
        exit 1
        ;;
esac

echo ""
echo "Load test finished. Final pod/HPA status:"
kubectl get hpa -n spring-poc
kubectl get pods -n spring-poc
