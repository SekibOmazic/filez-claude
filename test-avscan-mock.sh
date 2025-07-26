#!/bin/bash
# test-avscan-mock.sh
# Script to test the AVScan mock service directly

echo "🧪 Testing AVScan Mock Service"
echo "================================"

# Test 1: Health check
echo "1. Testing health check..."
curl -s http://localhost:8081/health
echo -e "\n"

# Test 2: Test endpoint
echo "2. Testing test endpoint..."
curl -s http://localhost:8081/test
echo -e "\n"

# Test 3: Debug endpoint with small data
echo "3. Testing debug endpoint..."
echo "Hello World" | curl -s -X POST http://localhost:8081/debug \
  -H "Content-Type: application/octet-stream" \
  --data-binary @-
echo -e "\n"

# Test 4: Scan endpoint with callback (simulated)
echo "4. Testing scan endpoint..."
echo "This is test file content for AVScan" | curl -s -X POST http://localhost:8081/scan \
  -H "Content-Type: application/octet-stream" \
  -H "targetUrl: http://httpbin.org/post" \
  -H "scan-reference-id: test-ref-123" \
  -H "original-filename: test.txt" \
  -H "original-content-type: text/plain" \
  --data-binary @-
echo -e "\n"

echo "✅ Tests completed!"
echo ""
echo "If any test fails, check the AVScan mock service logs for details."
echo "The service should be running on http://localhost:8081"