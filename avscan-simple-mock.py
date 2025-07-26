#!/usr/bin/env python3
"""
Ultra-Simple AVScan Mock Service
This version uses Flask's request.stream.read() in a very simple way to avoid chunked encoding issues.
"""

from flask import Flask, request, Response
import requests
import time
import random
import logging

app = Flask(__name__)
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

@app.route('/scan', methods=['POST'])
def scan_file():
    """
    Ultra-simple scan endpoint that reads the entire stream at once.
    """
    try:
        # Get required headers
        target_url = request.headers.get('targetUrl')
        if not target_url:
            logger.error("❌ Missing targetUrl header")
            return "Missing targetUrl header", 400

        scan_ref_id = request.headers.get('scan-reference-id', '')
        original_filename = request.headers.get('original-filename', 'unknown')
        original_content_type = request.headers.get('original-content-type', 'application/octet-stream')

        logger.info(f"🔍 Scanning: {original_filename} (ref: {scan_ref_id})")
        logger.info(f"📞 Target: {target_url}")

        # Log all headers for debugging
        logger.info("📋 Headers received:")
        for header, value in request.headers:
            logger.info(f"   {header}: {value}")

        # Try the SIMPLEST possible approach - read everything at once
        try:
            logger.info("📖 Reading request data...")

            # Method 1: Try request.data first (Flask's simplest method)
            file_content = request.data

            if not file_content:
                logger.info("📖 request.data was empty, trying get_data()...")
                file_content = request.get_data()

            if not file_content:
                logger.info("📖 get_data() was empty, trying stream.read()...")
                file_content = request.stream.read()

            if not file_content:
                logger.error("❌ All read methods returned empty data")
                return "No file content received", 400

        except Exception as e:
            logger.error(f"❌ Error reading data: {e}")
            return f"Error reading request: {str(e)}", 400

        file_size = len(file_content)
        logger.info(f"✅ Successfully read {file_size} bytes")

        if file_size == 0:
            logger.error("❌ File content is empty")
            return "Empty file received", 400

        # Show first few bytes for debugging
        preview = file_content[:50] if len(file_content) >= 50 else file_content
        logger.info(f"📄 Content preview: {preview}")

        # Simulate quick scan
        scan_time = random.uniform(0.5, 2.0)
        logger.info(f"⏱️ Simulating {scan_time:.1f}s scan...")
        time.sleep(scan_time)

        # 99% clean rate
        if random.random() < 0.01:
            logger.warning(f"🦠 Simulated infection: {original_filename}")
            return "File is infected", 200

        # File is clean - forward to callback
        logger.info(f"✅ File clean, forwarding {file_size} bytes to callback")

        try:
            callback_response = requests.post(
                target_url,
                data=file_content,
                headers={
                    'Content-Type': 'application/octet-stream',
                    'scan-reference-id': scan_ref_id,
                    'scan-result': 'CLEAN',
                    'original-filename': original_filename,
                    'original-content-type': original_content_type,
                    'Content-Length': str(file_size)
                },
                timeout=300
            )

            if callback_response.status_code == 200:
                logger.info(f"✅ Callback successful: {scan_ref_id}")
                return f"File scanned and forwarded: {file_size} bytes", 200
            else:
                logger.error(f"❌ Callback failed: {callback_response.status_code} - {callback_response.text}")
                return f"Callback failed: {callback_response.status_code}", 500

        except Exception as e:
            logger.error(f"❌ Callback error: {e}")
            return f"Callback error: {str(e)}", 500

    except Exception as e:
        logger.error(f"❌ Unexpected error: {e}", exc_info=True)
        return f"Internal error: {str(e)}", 500

@app.route('/health', methods=['GET'])
def health_check():
    """Health check"""
    return "Ultra-Simple AVScan Mock is running", 200

@app.route('/debug', methods=['POST'])
def debug_request():
    """Debug endpoint"""
    logger.info("🐛 DEBUG REQUEST")
    logger.info(f"   Method: {request.method}")
    logger.info(f"   Content-Type: {request.content_type}")
    logger.info(f"   Content-Length: {request.content_length}")

    try:
        data = request.data
        logger.info(f"   Data length: {len(data)} bytes")
        if data:
            logger.info(f"   First 100 bytes: {data[:100]}")
        return f"Debug: received {len(data)} bytes", 200
    except Exception as e:
        logger.error(f"   Debug error: {e}")
        return f"Debug error: {str(e)}", 400

if __name__ == '__main__':
    print("🚀 Starting Ultra-Simple AVScan Mock on port 8081")
    print("   Features: Minimal chunked handling, comprehensive logging")
    app.run(host='0.0.0.0', port=8081, debug=False, threaded=True)