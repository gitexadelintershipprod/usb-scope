FROM eclipse-temurin:17-jdk-jammy@sha256:81336a07f69ac361c7f278183b195e32037ebdb532684996806265a3b47ef516 AS tools
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git ca-certificates binutils && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV GRADLE_USER_HOME=/cache/gradle
RUN mkdir -p /opt/android-sdk/cmdline-tools && curl -fL --retry 3 https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip -o /tmp/tools.zip && echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  /tmp/tools.zip' | sha256sum -c - && unzip -q /tmp/tools.zip -d /opt/android-sdk/cmdline-tools && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest && rm /tmp/tools.zip
ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/37.0.0:$PATH
RUN yes | sdkmanager --licenses >/dev/null
FROM tools AS sdk
RUN sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0' 'platform-tools'
RUN sdkmanager 'ndk;29.0.14206865' 'cmake;3.22.1'
WORKDIR /src
