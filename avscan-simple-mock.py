#!/usr/bin/env python3
"""
Improved AVScan Mock Service with better connection handling
Fixed version that properly handles streaming requests from Spring WebFlux
"""

from flask import Flask, request, Response
import requests
import time
import random
import logging
import traceback
from werkzeug.exceptions import BadRequest
import socket

app = Flask(__name__)

# Configure detailed logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Also log werkzeug for HTTP request details
werkzeug_logger = logging.getLogger('werkzeug')
werkzeug_logger.setLevel(logging.INFO)

def test_callback_connectivity(target_url):
    """Test if the callback URL is reachable"""
    try:
        # Extract host and port from URL
        if target_url.startswith('http://'):
            url_part = target_url[7:]  # Remove http://
        elif target_url.startswith('https://'):
            url_part = target_url[8:]  # Remove https://
        else:
            url_part = target_url

        # Split host and path
        if '/' in url_part:
            host_port = url_part.split('/')[0]
        else:
            host_port = url_part

        if ':' in host_port:
            host, port = host_port.split(':')
            port = int(port)
        else:
            host = host_port
            port = 80 if target_url.startswith('http://') else 443

        logger.info(f"🔌 Testing connectivity to {host}:{port}")

        # Test socket connection
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(5)
        result = sock.connect_ex((host, port))
        sock.close()

        if result == 0:
            logger.info(f"✅ Connection successful to {host}:{port}")
            return True
        else:
            logger.error(f"❌ Connection failed to {host}:{port} - error code: {result}")
            return False

    except Exception as e:
        logger.error(f"❌ Connectivity test error: {e}")
        return False

@app.route('/scan', methods=['POST'])
def scan_file():
    """
    Improved scan endpoint with better error handling and connectivity testing
    """
    logger.info("=" * 60)
    logger.info("🔍 AVScan request received")
    logger.info("=" * 60)

    try:
        # Log all request details
        logger.info(f"📋 Request method: {request.method}")
        logger.info(f"📋 Content-Type: {request.content_type}")
        logger.info(f"📋 Content-Length: {request.content_length}")
        logger.info(f"📋 Remote address: {request.remote_addr}")

        # Log all headers
        logger.info("📋 All headers:")
        for header_name, header_value in request.headers:
            logger.info(f"   {header_name}: {header_value}")

        # Get required headers with validation
        target_url = request.headers.get('targetUrl')
        if not target_url:
            logger.error("❌ Missing 'targetUrl' header")
            return Response("Missing targetUrl header", status=400, content_type='text/plain')

        scan_ref_id = request.headers.get('scan-reference-id', '')
        original_filename = request.headers.get('original-filename', 'unknown')
        original_content_type = request.headers.get('original-content-type', 'application/octet-stream')

        logger.info(f"🎯 Target URL: {target_url}")
        logger.info(f"🔖 Scan Reference ID: {scan_ref_id}")
        logger.info(f"📄 Original Filename: {original_filename}")
        logger.info(f"📄 Original Content-Type: {original_content_type}")

        # Test callback connectivity BEFORE reading the file
        logger.info("🔌 Testing callback connectivity...")
        if not test_callback_connectivity(target_url):
            logger.error("❌ Cannot reach callback URL. Check network connectivity.")
            return Response("Cannot reach callback URL - check network configuration",
                          status=500, content_type='text/plain')

        # Read the request body with multiple fallback methods
        logger.info("📖 Reading request body...")
        file_content = None

        try:
            # Method 1: Try request.data (most reliable for binary data)
            logger.info("📖 Trying request.data...")
            file_content = request.data
            logger.info(f"📖 request.data returned {len(file_content)} bytes")

            if not file_content:
                # Method 2: Try get_data()
                logger.info("📖 request.data empty, trying get_data()...")
                file_content = request.get_data()
                logger.info(f"📖 get_data() returned {len(file_content)} bytes")

            if not file_content:
                # Method 3: Try reading from stream
                logger.info("📖 get_data() empty, trying stream.read()...")
                file_content = request.stream.read()
                logger.info(f"📖 stream.read() returned {len(file_content)} bytes")

            if not file_content:
                # Method 4: Try reading with a limit
                logger.info("📖 stream.read() empty, trying limited read...")
                file_content = request.stream.read(100 * 1024 * 1024)  # 100MB limit
                logger.info(f"📖 limited read returned {len(file_content)} bytes")

        except Exception as read_error:
            logger.error(f"❌ Error reading request body: {read_error}")
            logger.error(f"❌ Error type: {type(read_error)}")
            logger.error(f"❌ Error traceback: {traceback.format_exc()}")
            return Response(f"Error reading request body: {str(read_error)}", status=400, content_type='text/plain')

        if not file_content:
            logger.error("❌ No file content received after all attempts")
            return Response("No file content received", status=400, content_type='text/plain')

        file_size = len(file_content)
        logger.info(f"✅ Successfully read {file_size} bytes")

        if file_size == 0:
            logger.error("❌ File content is empty")
            return Response("Empty file content", status=400, content_type='text/plain')

        # Show content preview for debugging (first 50 bytes)
        if file_size > 0:
            preview_size = min(50, file_size)
            preview = file_content[:preview_size]
            logger.info(f"📄 Content preview ({preview_size} bytes): {preview}")

            # Check if it looks like binary content
            try:
                preview_str = preview.decode('utf-8', errors='ignore')
                logger.info(f"📄 Content preview (text): {repr(preview_str)}")
            except:
                logger.info("📄 Content appears to be binary data")

        # Simulate virus scanning with random delay
        scan_time = random.uniform(0.5, 2.0)
        logger.info(f"⏱️ Simulating virus scan for {scan_time:.1f} seconds...")
        time.sleep(scan_time)

        # 99% clean rate (1% infected for testing)
        if random.random() < 0.01:
            logger.warning(f"🦠 Simulated virus detected in: {original_filename}")
            return Response("INFECTED: Simulated virus detected", status=200, content_type='text/plain')

        # File is clean - forward to callback
        logger.info(f"✅ File is clean, forwarding {file_size} bytes to callback...")

        try:
            # Make callback request with proper headers
            callback_headers = {
                'Content-Type': 'application/octet-stream',
                'scan-reference-id': scan_ref_id,
                'scan-result': 'CLEAN',
                'original-filename': original_filename,
                'original-content-type': original_content_type,
                'Content-Length': str(file_size),
                'User-Agent': 'AVScan-Mock/1.0'
            }

            logger.info("📞 Making callback request...")
            logger.info(f"📞 Callback URL: {target_url}")
            logger.info("📞 Callback headers:")
            for h_name, h_value in callback_headers.items():
                logger.info(f"   {h_name}: {h_value}")

            # Use a longer timeout and retry logic
            max_retries = 3
            retry_delay = 1

            for attempt in range(max_retries):
                try:
                    logger.info(f"📞 Callback attempt {attempt + 1}/{max_retries}")

                    callback_response = requests.post(
                        target_url,
                        data=file_content,  # Send as raw bytes
                        headers=callback_headers,
                        timeout=300,  # 5 minute timeout
                        stream=False  # Don't stream the response (we need the status)
                    )

                    logger.info(f"📞 Callback response status: {callback_response.status_code}")
                    logger.info(f"📞 Callback response headers: {dict(callback_response.headers)}")

                    if callback_response.text:
                        logger.info(f"📞 Callback response body: {callback_response.text[:200]}...")

                    if callback_response.status_code == 200:
                        logger.info(f"✅ Callback successful for: {scan_ref_id}")
                        return Response(f"SUCCESS: File scanned and forwarded ({file_size} bytes)",
                                      status=200, content_type='text/plain')
                    else:
                        logger.error(f"❌ Callback failed with status {callback_response.status_code}")
                        logger.error(f"❌ Callback error response: {callback_response.text}")

                        if attempt < max_retries - 1:
                            logger.info(f"🔄 Retrying in {retry_delay} seconds...")
                            time.sleep(retry_delay)
                            retry_delay *= 2  # Exponential backoff
                        else:
                            return Response(f"Callback failed after {max_retries} attempts: HTTP {callback_response.status_code}",
                                          status=500, content_type='text/plain')

                except requests.exceptions.Timeout:
                    logger.error(f"❌ Callback request timed out (attempt {attempt + 1})")
                    if attempt < max_retries - 1:
                        logger.info(f"🔄 Retrying in {retry_delay} seconds...")
                        time.sleep(retry_delay)
                        retry_delay *= 2
                    else:
                        return Response("Callback timeout after retries", status=500, content_type='text/plain')

                except requests.exceptions.ConnectionError as conn_err:
                    logger.error(f"❌ Callback connection error (attempt {attempt + 1}): {conn_err}")
                    if attempt < max_retries - 1:
                        logger.info(f"🔄 Retrying in {retry_delay} seconds...")
                        time.sleep(retry_delay)
                        retry_delay *= 2
                    else:
                        return Response(f"Callback connection error after retries: {str(conn_err)}",
                                      status=500, content_type='text/plain')

        except Exception as callback_error:
            logger.error(f"❌ Unexpected callback error: {callback_error}")
            logger.error(f"❌ Callback error traceback: {traceback.format_exc()}")
            return Response(f"Callback error: {str(callback_error)}", status=500, content_type='text/plain')

    except BadRequest as br:
        logger.error(f"❌ Bad request: {br}")
        return Response(f"Bad request: {str(br)}", status=400, content_type='text/plain')

    except Exception as e:
        logger.error(f"❌ Unexpected error in scan endpoint: {e}")
        logger.error(f"❌ Error type: {type(e)}")
        logger.error(f"❌ Full traceback: {traceback.format_exc()}")
        return Response(f"Internal server error: {str(e)}", status=500, content_type='text/plain')

@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint"""
    logger.info("💚 Health check requested")
    return Response("Improved AVScan Mock is running", status=200, content_type='text/plain')

@app.route('/debug', methods=['POST'])
def debug_request():
    """Debug endpoint to test request handling"""
    logger.info("🐛 Debug endpoint called")

    try:
        logger.info(f"🐛 Method: {request.method}")
        logger.info(f"🐛 Content-Type: {request.content_type}")
        logger.info(f"🐛 Content-Length: {request.content_length}")

        # Try to read data
        data = request.data
        logger.info(f"🐛 Data length: {len(data)} bytes")

        if data and len(data) > 0:
            preview = data[:100] if len(data) > 100 else data
            logger.info(f"🐛 Data preview: {preview}")

        return Response(f"Debug OK: received {len(data)} bytes", status=200, content_type='text/plain')

    except Exception as e:
        logger.error(f"🐛 Debug error: {e}")
        logger.error(f"🐛 Debug traceback: {traceback.format_exc()}")
        return Response(f"Debug error: {str(e)}", status=400, content_type='text/plain')

@app.route('/test', methods=['GET'])
def test_endpoint():
    """Simple test endpoint"""
    return Response("AVScan Mock Test Endpoint OK", status=200, content_type='text/plain')

@app.route('/connectivity-test', methods=['GET'])
def connectivity_test():
    """Test connectivity to the main application"""
    test_url = request.args.get('url', 'http://filez:8080/actuator/health')
    logger.info(f"🔌 Testing connectivity to: {test_url}")

    if test_callback_connectivity(test_url):
        return Response(f"✅ Can reach {test_url}", status=200, content_type='text/plain')
    else:
        return Response(f"❌ Cannot reach {test_url}", status=500, content_type='text/plain')

if __name__ == '__main__':
    print("🚀 Starting Improved AVScan Mock Service")
    print("   Port: 8081")
    print("   Features:")
    print("   - Comprehensive request logging")
    print("   - Multiple content reading strategies")
    print("   - Better error handling with retries")
    print("   - Detailed callback debugging")
    print("   - Connectivity testing")
    print("   - Health check: http://localhost:8081/health")
    print("   - Debug endpoint: http://localhost:8081/debug")
    print("   - Test endpoint: http://localhost:8081/test")
    print("   - Connectivity test: http://localhost:8081/connectivity-test?url=http://filez:8080/actuator/health")

    app.run(
        host='0.0.0.0',
        port=8081,
        debug=False,  # Keep debug=False for cleaner logging
        threaded=True,
        use_reloader=False  # Prevent double startup messages
    )