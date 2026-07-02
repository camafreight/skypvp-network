FROM eclipse-temurin:25-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends gettext-base \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /data/runtime
COPY config/server-templates/proxy /opt/skypvp/server-template
COPY config/floodgate/key.pem /opt/skypvp/floodgate-key/key.pem
COPY infra/scripts/lib/render-server-templates.sh /opt/skypvp/lib/render-server-templates.sh
COPY infra/scripts/lib/install-floodgate-key.sh /opt/skypvp/lib/install-floodgate-key.sh
COPY infra/scripts/proxy-entrypoint.sh /opt/skypvp/entrypoint.sh
RUN chmod +x /opt/skypvp/entrypoint.sh /opt/skypvp/lib/render-server-templates.sh /opt/skypvp/lib/install-floodgate-key.sh
