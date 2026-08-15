# JADX Web for Safari

Mobile web interface for the existing JADX command line build. It is designed to run as a small Docker service and be opened from Safari on iPhone or iPad.

## Features

- Upload APK, DEX, AAB, JAR, ZIP, AAR, XAPK and APKM files
- Decompile with the existing JADX CLI
- Browse the generated file tree from Safari
- Open AndroidManifest.xml, Java/XML/resources and other text files
- Search files by path
- Copy decompiled source code
- Temporary server-side sessions with automatic cleanup
- 100 MB upload limit by default and limited concurrent decompilation jobs

## Run

From the repository root:

```bash
docker build -f jadx-web/Dockerfile -t jadx-web .
docker run --rm -p 8080:8080 jadx-web
```

Open `http://localhost:8080` in a browser.

For iPhone/Safari deployment, host the Docker container behind HTTPS (for example on a container host) and open the resulting HTTPS URL in Safari.

## Configuration

Environment variables:

- `PORT` - default `8080`
- `MAX_UPLOAD_BYTES` - default `104857600` (100 MB)
- `MAX_CONCURRENT_DECOMPILES` - default `2`
- `SESSION_TTL_MINUTES` - default `30`
- `JADX_BIN` - path to the JADX executable
- `STATIC_ROOT` - path to the web UI files
