# tile_service testing container: Tomcat 9 on a Java 21 JRE serving a locally
# built image_tiler.war (localhost-fscache configuration) on port 8080, plus the
# TileCLI jar. Nothing is compiled here and no internal repository is needed.
#
# Build the artifacts first (public dependencies only):
#   bash tests/e2e/scripts/build-product.sh
# Then: ./testing-container/run.sh
FROM tomcat:9.0-jre21-temurin

# the localhost-fscache web.xml uses paths relative to Tomcat's working directory:
# fs_input_dir=test, fs_cache_loc=test/cache, lru_disk_cache_dir=tmp
WORKDIR /usr/local/tomcat
RUN mkdir -p test/cache tmp /opt/tile_service && rm -rf webapps/*

COPY tests/e2e/.cache/build/image_tiler.war webapps/image_tiler.war
COPY tests/e2e/.cache/build/image_tiler.jar /opt/tile_service/image_tiler.jar

ENV CATALINA_OPTS="-Xmx4g -Djava.awt.headless=true"
EXPOSE 8080
CMD ["catalina.sh", "run"]
