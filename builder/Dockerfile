FROM ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31

USER root

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends jq libxml2-utils openssl \
    && rm -rf /var/lib/apt/lists/* \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "build-tools;34.0.0" "ndk;28.2.13676358" "cmake;3.22.1" \
    && mkdir -p /opt/bundletool \
    && curl --fail --location --retry 3 \
        https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar \
        -o /opt/bundletool/bundletool-all-1.18.3.jar \
    && printf '%s  %s\n' \
        a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29 \
        /opt/bundletool/bundletool-all-1.18.3.jar | sha256sum --check --status

ENV ANDROID_NDK_HOME=/opt/android-sdk-linux/ndk/28.2.13676358
ENV BUNDLETOOL_JAR=/opt/bundletool/bundletool-all-1.18.3.jar
ENV PATH=/opt/android-sdk-linux/cmake/3.22.1/bin:/opt/android-sdk-linux/cmdline-tools/latest/bin:/opt/android-sdk-linux/platform-tools:${PATH}

COPY builder/build-in-container.sh /usr/local/bin/openautolink-build
RUN chmod 0755 /usr/local/bin/openautolink-build

LABEL org.opencontainers.image.source="https://github.com/mossyhub/openautolink"
LABEL org.opencontainers.image.description="Reproducible OpenAutoLink personal AAB builder"

ENTRYPOINT ["/usr/local/bin/openautolink-build"]
