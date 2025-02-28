# This file can be used to build an (unofficial) Docker image of Apache Solr.
#
# The primary purpose of this file, is for use by Solr developers, with a java/gradle development env, who
# wish to build customized -- or "patched" docker images of Solr.  For this type of usecase, this file
# will be used automatically by gradle to build docker images from your local src.
#   Example:
#     ./gradlew dockerBuild
#
# For most Solr users, using this Dockerfile is not recommended: pre-built docker images of Solr are 
# available at https://hub.docker.com/_/solr -- however this file can be used to build docker images from
# a Solr release artifact -- either from a remote TGZ file, or from an TGZ artifact you have downloaded
# locally.
#    Example:
#      docker build -f solr-X.Y.Z/docker/Dockerfile https://www.apache.org/dyn/closer.lua/solr/X.Y.Z/solr-X.Y.Z.tgz
#    Example:
#      docker build -f solr-X.Y.Z/docker/Dockerfile - < solr-X.Y.Z.tgz


ARG BASE_IMAGE=openjdk:11-jre-slim

FROM $BASE_IMAGE as input

COPY / /opt/

# remove what we don't want; ensure permissions are right
#  TODO; arguably these permissions should have been set correctly previously in the TAR
RUN set -ex; \
  (cd /opt; ln -s solr-*/ solr); \
  rm -Rf /opt/solr/docs /opt/solr/docker/Dockerfile /opt/solr/dist/{solr-solrj-*.jar,solrj-lib,solr-test-framework-*.jar,test-framework}; \
  find /opt/solr/ -type d -print0 | xargs -0 chmod 0755; \
  find /opt/solr/ -type f -print0 | xargs -0 chmod 0644; \
  chmod -R 0755 /opt/solr/docker/scripts /opt/solr/bin /opt/solr/contrib/prometheus-exporter/bin/solr-exporter /opt/solr/server/scripts/cloud-scripts

FROM $BASE_IMAGE

LABEL maintainer="The Apache Lucene/Solr Project"
LABEL repository="https://github.com/apache/lucene-solr"

# Override the default github URL to provide a mirror for github releases.
ARG GITHUB_URL=github.com

RUN set -ex; \
    apt-get update; \
    apt-get -y install acl dirmngr lsof procps wget netcat gosu tini; \
    rm -rf /var/lib/apt/lists/*; \
    cd /usr/local/bin; wget -nv https://${GITHUB_URL}/apangin/jattach/releases/download/v1.5/jattach; chmod 755 jattach; \
    echo >jattach.sha512 "d8eedbb3e192a8596c08efedff99b9acf1075331e1747107c07cdb1718db2abe259ef168109e46bd4cf80d47d43028ff469f95e6ddcbdda4d7ffa73a20e852f9  jattach"; \
    sha512sum -c jattach.sha512; rm jattach.sha512

ENV SOLR_USER="solr" \
    SOLR_UID="8983" \
    SOLR_GROUP="solr" \
    SOLR_GID="8983" \
    PATH="/opt/solr/bin:/opt/solr/docker/scripts:/opt/solr/contrib/prometheus-exporter/bin:$PATH" \
    SOLR_INCLUDE=/etc/default/solr.in.sh \
    SOLR_HOME=/var/solr/data \
    SOLR_PID_DIR=/var/solr \
    SOLR_LOGS_DIR=/var/solr/logs \
    LOG4J_PROPS=/var/solr/log4j2.xml \
    SOLR_JETTY_HOST="0.0.0.0"

RUN set -ex; \
  groupadd -r --gid "$SOLR_GID" "$SOLR_GROUP"; \
  useradd -r --uid "$SOLR_UID" --gid "$SOLR_GID" "$SOLR_USER"

COPY --from=input /opt/ /opt/

RUN set -ex; \
  mkdir -p /opt/solr/server/solr/lib /docker-entrypoint-initdb.d; \
  cp /opt/solr/bin/solr.in.sh /etc/default/solr.in.sh; \
  mv /opt/solr/bin/solr.in.sh /opt/solr/bin/solr.in.sh.orig; \
  mv /opt/solr/bin/solr.in.cmd /opt/solr/bin/solr.in.cmd.orig; \
  chmod 0664 /etc/default/solr.in.sh; \
  mkdir -p -m0770 /var/solr; \
  chown -R "$SOLR_USER:0" /var/solr;

VOLUME /var/solr
EXPOSE 8983
WORKDIR /opt/solr
USER $SOLR_USER

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["solr-foreground"]
