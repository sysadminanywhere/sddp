FROM node:22-alpine
COPY plugin.mjs /opt/sddp/plugin.mjs
RUN printf '#!/bin/sh\nexec node /opt/sddp/plugin.mjs "$@"\n' > /usr/local/bin/plugin \
    && chmod 755 /usr/local/bin/plugin
