#!/usr/bin/env bash
# Build and run the tile_service testing container (Tomcat 9, Java 21).
# Packages the locally built war and jar; serves the images in a local directory.
#
# Usage:
#   ./testing-container/run.sh                    # serve tests/e2e/.cache/data on http://localhost:8080
#   ./testing-container/run.sh --data DIR         # serve another directory of images
#   ./testing-container/run.sh --port 9090        # another host port
#   ./testing-container/run.sh -- [docker args]   # extra docker run arguments
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
IMAGE_NAME="tile-service-test:tomcat9-java21"
DATA_DIR="$PROJECT_ROOT/tests/e2e/.cache/data"
PORT=8080
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --data) DATA_DIR="$(cd "$2" && pwd)"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        --) shift; EXTRA_ARGS=("$@"); break ;;
        *) EXTRA_ARGS+=("$1"); shift ;;
    esac
done

for f in image_tiler.war image_tiler.jar; do
    if [[ ! -f "$PROJECT_ROOT/tests/e2e/.cache/build/$f" ]]; then
        echo "ERROR: tests/e2e/.cache/build/$f not found"
        echo ""
        echo "Build it first (public dependencies only):"
        echo "  bash tests/e2e/scripts/build-product.sh"
        exit 1
    fi
done

echo "Building $IMAGE_NAME..."
docker build -t "$IMAGE_NAME" -f "$SCRIPT_DIR/Dockerfile" "$PROJECT_ROOT"

echo ""
echo "Serving images from $DATA_DIR (mounted read-only as fs_input_dir)"
echo "  http://localhost:$PORT/image_tiler/version"
echo "  http://localhost:$PORT/image_tiler?image=<file>&viewer=true&proxy=http"
echo "  docker exec -it <container> java -jar /opt/tile_service/image_tiler.jar -h"
echo ""

docker run --rm -it -p "$PORT:8080" \
    -v "$DATA_DIR:/usr/local/tomcat/test/data:ro" \
    ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"} "$IMAGE_NAME"
