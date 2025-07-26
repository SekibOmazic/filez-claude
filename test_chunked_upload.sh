#!/bin/bash
# Test script to verify chunked upload handling

echo "Testing AVScan mock service with chunked data..."

# Create test data
echo "Hello, this is test data for chunked upload!" > test_file.txt

# Test 1: Direct POST to debug endpoint
echo "=== Test 1: Debug endpoint ==="
curl -X POST \
  http://localhost:8081/debug \
  -H "Content-Type: application/octet-stream" \
  --data-binary @test_file.txt \
  -v

echo -e "\n\n=== Test 2: Chunked transfer encoding ==="
# Test 2: Force chunked encoding
curl -X POST \
  http://localhost:8081/debug \
  -H "Content-Type: application/octet-stream" \
  -H "Transfer-Encoding: chunked" \
  --data-binary @test_file.txt \
  -v

echo -e "\n\n=== Test 3: Scan endpoint ==="
# Test 3: Test the actual scan endpoint
curl -X POST \
  http://localhost:8081/scan \
  -H "Content-Type: application/octet-stream" \
  -H "targetUrl: http://httpbin.org/post" \
  -H "scan-reference-id: test-123" \
  -H "original-filename: test_file.txt" \
  -H "original-content-type: text/plain" \
  --data-binary @test_file.txt \
  -v

# Cleanup
rm test_file.txt

echo "Test completed!"