# Filez - Scalable File Management Service

A high-performance, reactive file management service built with Spring Boot WebFlux that supports virus scanning and large file uploads/downloads with S3-compatible storage.

## Features

- **Non-blocking file operations** - Handles large files (5GB+) without memory buffering
- **Virus scanning integration** - Streams files through AVScan service before storage
- **S3-compatible storage** - Works with AWS S3, MinIO, or any S3-compatible service
- **Reactive architecture** - Built with Spring WebFlux for maximum scalability
- **PostgreSQL metadata storage** - Tracks file metadata with R2DBC
- **Stateless design** - Horizontally scalable with no session state
- **Testcontainers integration** - Easy local development and testing

## Architecture

```
Client → Upload → [Stream] → AVScan → [Stream] → Callback → [Stream] → S3
                       ↓
                  PostgreSQL (metadata)
```

### Key Components

1. **FileController** - REST endpoints for upload/download/status
2. **FileService** - Business logic orchestration
3. **AVScanService** - Integration with virus scanning service
4. **S3Service** - S3 operations with streaming
5. **CleanupService** - Scheduled cleanup of failed uploads

## API Endpoints

### Upload File
```http
POST /api/v1/files/upload
Content-Type: multipart/form-data

file: <binary data>
metadata: {
  "filename": "document.pdf",
  "contentType": "application/pdf"
}
```

### Get File Status
```http
GET /api/v1/files/{fileId}/status
```

### Download File
```http
GET /api/v1/files/{fileId}/download
```

### AVScan Callback (Internal)
```http
POST /api/v1/files/upload-scanned?ref={scanReferenceId}
Content-Type: application/octet-stream
```

## Quick Start

### Prerequisites
- Java 17+
- Docker & Docker Compose
- Gradle 8+

### Local Development

1. **Clone repository**
```bash
git clone <repository-url>
cd filez
```

2. **Start with Docker Compose**
```bash
docker-compose up -d postgres s3mock avscan-mock
```

3. **Run application**
```bash
./gradlew bootRun
```

The application uses Testcontainers configuration automatically for local development.

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_R2DBC_URL` | PostgreSQL R2DBC URL | `r2dbc:postgresql://localhost:5432/filemanager` |
| `APP_S3_ENDPOINT` | S3 endpoint URL | `http://localhost:9090` |
| `APP_S3_BUCKET` | S3 bucket name | `filemanager-bucket` |
| `APP_AV_SCAN_ENDPOINT` | AVScan service URL | `http://localhost:8081/scan` |
| `APP_CALLBACK_BASE_URL` | Callback base URL | `http://localhost:8080` |

## File Upload Flow

1. **Client uploads file** → Multipart request to `/upload`
2. **Metadata saved** → PostgreSQL with `UPLOADING` status
3. **Stream to AVScan** → File content streamed to virus scanner
4. **Status updated** → Database status changed to `SCANNING`
5. **AVScan processes** → Scans file and streams clean content to callback
6. **Stream to S3** → Clean content streamed directly to S3 storage
7. **Metadata updated** → Final file size and `CLEAN` status saved

## Database Schema

```sql
CREATE TABLE files (
    id UUID PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT,
    s3_key VARCHAR(500) NOT NULL,
    upload_session_id UUID NOT NULL,
    status file_status NOT NULL,
    scan_reference_id VARCHAR(255) UNIQUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    scanned_at TIMESTAMP
);
```

## File Status States

- `UPLOADING` - Initial upload received, metadata saved
- `SCANNING` - File sent to AVScan service
- `CLEAN` - Scan completed, file stored in S3
- `INFECTED` - File contains malware
- `FAILED` - Upload or scan failed

## Performance Characteristics

- **Memory usage** - Constant, independent of file size
- **Throughput** - Limited by network bandwidth, not application
- **Concurrency** - Handles thousands of concurrent uploads
- **Scalability** - Stateless, horizontally scalable

## Configuration

Key configuration properties in `application.yml`:

```yaml
spring:
  webflux:
    multipart:
      max-in-memory-size: 1MB      # Minimal memory buffering
      max-disk-usage-per-part: 0   # No disk buffering

app:
  upload:
    max-file-size: 5GB
    temp-cleanup-interval: 300s
  av-scan:
    timeout: 300s
```

## Monitoring

- **Health checks** - `/actuator/health`
- **Metrics** - `/actuator/metrics`
- **Cleanup jobs** - Automatic cleanup of failed uploads

## Security Considerations

- File type validation
- Size limits with streaming validation
- Callback endpoint authentication (scan reference validation)
- Resource cleanup to prevent memory leaks

## Testing

The application includes Testcontainers configuration for:
- PostgreSQL database
- S3Mock for S3-compatible storage
- Integration testing support

```bash
./gradlew test
```

## Production Deployment

1. **Configure external services**
    - PostgreSQL database
    - S3-compatible storage
    - AVScan service

2. **Set environment variables**
    - Database connection
    - S3 credentials
    - Service endpoints

3. **Deploy with Docker**
```bash
docker build -t filez .
docker run -p 8080:8080 \
  -e SPRING_R2DBC_URL=r2dbc:postgresql://prod-db:5432/filemanager \
  -e APP_S3_ENDPOINT=https://s3.amazonaws.com \
  filez
```

## License

MIT License