#!/bin/bash
# Network diagnostic script for Communication Card server
# Run on the Ubuntu server to diagnose 503 issue

echo "=== Network Diagnostic Script ==="
echo ""

echo "--- 1. Checking what's listening on port 8080 ---"
sudo ss -tlnp | grep 8080
echo ""

echo "--- 2. Checking if nginx/apache/caddy are running ---"
for svc in nginx apache2 caddy haproxy; do
    status=$(systemctl is-active $svc 2>/dev/null)
    if [ "$status" = "active" ]; then
        echo "WARNING: $svc is RUNNING - this may be intercepting port 8080!"
        sudo systemctl status $svc | grep "Active\|Listen"
    fi
done
echo ""

echo "--- 3. UFW firewall status ---"
sudo ufw status verbose
echo ""

echo "--- 4. iptables NAT rules (could redirect traffic) ---"
sudo iptables -t nat -L -n -v 2>/dev/null
echo ""

echo "--- 5. All processes listening (sorted by port) ---"
sudo ss -tlnp | sort -k4
echo ""

echo "--- 6. Testing local connectivity ---"
curl -s -o /dev/null -w "HTTP Status: %{http_code}\n" http://localhost:8080/
echo ""

echo "--- 7. Network interfaces ---"
ip addr show | grep "inet \|^[0-9]"
echo ""

echo "--- 8. Starting tcpdump (10 second capture on all interfaces, port 8080) ---"
echo "Access http://$(curl -s ifconfig.me 2>/dev/null):8080/ from your browser NOW..."
echo "Waiting 10 seconds..."
sudo timeout 10 tcpdump -i any port 8080 -n 2>&1 | head -30
echo ""
echo "=== If no packets appeared above, security group is blocking external traffic ==="
echo "=== If packets appeared but server showed no logs, something intercepted the request ==="
