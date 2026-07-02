# Folia-based extraction image (multi-world Aether Breach).
# Build with the same server-templates as Paper extraction; swap the runtime jar for Folia when available.
FROM eclipse-temurin:25-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends gettext-base \
 && rm -rf /var/lib/apt/lists/*
ENV SPVP_USE_FOLIA=true
# Heap is controlled via SPVP_JAVA_MIN/SPVP_JAVA_MAX (paper-entrypoint.sh); JAVA_OPTS is not read.
WORKDIR /data/runtime
COPY config/server-templates/extraction /opt/skypvp/server-template
COPY config/world-templates /opt/skypvp/world-templates
COPY config/map-templates /opt/skypvp/map-templates
COPY config/floodgate/key.pem /opt/skypvp/floodgate-key/key.pem
COPY infra/scripts/lib/render-server-templates.sh /opt/skypvp/lib/render-server-templates.sh
COPY infra/scripts/lib/install-floodgate-key.sh /opt/skypvp/lib/install-floodgate-key.sh
COPY infra/scripts/paper-entrypoint.sh /opt/skypvp/entrypoint.sh
RUN chmod +x /opt/skypvp/entrypoint.sh /opt/skypvp/lib/render-server-templates.sh /opt/skypvp/lib/install-floodgate-key.sh
