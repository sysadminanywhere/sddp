FROM alpine:3.20
RUN printf '#!/bin/sh\nif grep -q fail-test "$1"; then echo "fixture plugin failed" >&2; exit 7; fi\ncp "$1" "$2/result.json"\n' > /usr/local/bin/plugin && chmod 755 /usr/local/bin/plugin
