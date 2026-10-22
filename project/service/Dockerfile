FROM eclipse-temurin:21-jre-jammy
ENV AGENTIC_OLAP_HOME=/opt/agentic-olap
RUN groupadd --gid 10001 olap \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin olap \
    && install -d -o 10001 -g 10001 /var/lib/agentic-olap
# 上下文是精确 server-distribution 产物，不包含源码、业务数据和构建工具。
COPY java/ /opt/agentic-olap/java/
COPY runtime/ /opt/agentic-olap/runtime/
COPY licenses/ /opt/agentic-olap/licenses/
COPY distribution.json LICENSE /opt/agentic-olap/
USER 10001:10001
WORKDIR /var/lib/agentic-olap
EXPOSE 8000
ENTRYPOINT ["/opt/agentic-olap/runtime/agentic-olap-runtime", "start"]
CMD ["--config", "/etc/agentic-olap/server.json"]
