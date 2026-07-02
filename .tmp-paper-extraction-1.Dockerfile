FROM eclipse-temurin:25-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends gettext-base \
 && rm -rf /var/lib/apt/lists/*
ENV SPVP_USE_FOLIA=true
ENV JAVA_OPTS="-Xms8G -Xmx16G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200"
WORKDIR /data/runtime
COPY config/server-templates/extraction /opt/skypvp/server-template
COPY config/world-templates /opt/skypvp/world-templates
COPY config/map-templates /opt/skypvp/map-templates
COPY config/floodgate/key.pem /opt/skypvp/floodgate-key/key.pem
COPY infra/scripts/lib/render-server-templates.sh /opt/skypvp/lib/render-server-templates.sh
COPY infra/scripts/lib/install-floodgate-key.sh /opt/skypvp/lib/install-floodgate-key.sh
COPY infra/scripts/paper-entrypoint.sh /opt/skypvp/entrypoint.sh
RUN chmod +x /opt/skypvp/entrypoint.sh /opt/skypvp/lib/render-server-templates.sh /opt/skypvp/lib/install-floodgate-key.sh
