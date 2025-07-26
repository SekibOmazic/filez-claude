#!/usr/bin/env python3
"""
Simple AVScan Mock Service
A lightweight mock service that simulates virus scanning behavior.
"""

from flask import Flask, request, Response
import requests
import time
import random
import logging

app = Flask(__name__)
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

@app.route('/scan', methods=['POST'])
def scan_file():
    """
    Mock virus scanning endpoint.
    Receives file content and streams it to the callback URL after simulated scanning.
    """
    try:
        # Get target URL from headers
        target_url = request.headers.get('targetUrl')
        if not target_url:
            logger.error("Missing targetUrl header")
            return "Missing targetUrl header", 400

        # Get additional headers
        scan_ref_id = request.headers.get('scan-reference-id', '')
        original_filename = request.headers.get('original-filename', 'unknown')
        original_content_type = request.headers.get('original-content-type', 'application/octet-stream')

        logger.info(f"Scanning file: {original_filename} (ref: {scan_ref_id})")
        logger.info(f"Will forward to: {target_url}")

        # Read file content - handle both regular and chunked transfer encoding
        try:
            file_content = request.get_data()
            if not file_content:
                logger.error("No file content received")
                return "No file content received", 400
        except Exception as e:
            logger.error(f"Error reading request body: {str(e)}")
            return f"Error reading request body: {str(e)}", 400

        file_size = len(file_content)
        logger.info(f"Received file content: {file_size} bytes")

        # Simulate scanning delay (1-3 seconds)
        scan_time = random.uniform(1, 3)
        logger.info(f"Simulating virus scan for {scan_time:.2f} seconds...")
        time.sleep(scan_time)

        # Simulate scan result (99% clean, 1% infected for testing)
        is_infected = random.random() < 0.01

        if is_infected:
            logger.warning(f"Simulated INFECTED result for file: {original_filename}")
            return "File is infected - not forwarding", 200

        # File is clean - forward to callback URL
        logger.info(f"File is CLEAN - forwarding to callback: {target_url}")

        callback_headers = {
            'Content-Type': 'application/octet-stream',
            'scan-reference-id': scan_ref_id,
            'scan-result': 'CLEAN',
            'original-filename': original_filename,
            'original-content-type': original_content_type,
            'Content-Length': str(file_size)
        }

        # Stream content to callback URL with better error handling
        try:
            response = requests.post(
                target_url,
                data=file_content,
                headers=callback_headers,
                timeout=300,  # 5 minutes timeout
                stream=False  # Don't stream the response, just the request
            )

            if response.status_code == 200:
                logger.info(f"Successfully forwarded file to callback: {scan_ref_id}")
                return "File scanned and forwarded successfully", 200
            else:
                logger.error(f"Callback failed with status {response.status_code}: {response.text}")
                return f"Callback failed with status {response.status_code}", 500

        except requests.exceptions.ConnectionError as e:
            logger.error(f"Connection error forwarding to callback: {str(e)}")
            return f"Connection error: {str(e)}", 500
        except requests.exceptions.Timeout as e:
            logger.error(f"Timeout forwarding to callback: {str(e)}")
            return f"Timeout error: {str(e)}", 500
        except requests.exceptions.RequestException as e:
            logger.error(f"Request error forwarding to callback: {str(e)}")
            return f"Network error: {str(e)}", 500

    except Exception as e:
        logger.error(f"Unexpected error: {str(e)}", exc_info=True)
        return f"Internal error: {str(e)}", 500

@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint"""
    return "AVScan Mock Service is running", 200

if __name__ == '__main__':
    print("Starting AVScan Mock Service on port 8081...")
    print("This service will:")
    print("1. Receive file uploads on POST /scan")
    print("2. Simulate virus scanning (1-3 second delay)")
    print("3. Forward clean files to the callback URL")
    print("4. Randomly mark 1% of files as infected for testing")

    app.run(host='0.0.0.0', port=8081, debug=True)