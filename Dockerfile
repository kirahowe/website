# The whole site runs under babashka — the image is debian + the bb static
# binary + git (for cloning/pulling the content repo).
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends git curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# nextjournal/markdown became a bb built-in in 1.12.196; pin something newer.
ARG BB_VERSION=1.12.218
RUN curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install \
 && bash install --version ${BB_VERSION} --static \
 && rm install \
 && bb --version

WORKDIR /app
COPY bb.edn ./
COPY config ./config
COPY src ./src
COPY resources ./resources
# Fallback content so the image can boot even if the content clone fails;
# prod.edn points :content-path at the cloned content repo.
COPY example-content ./example-content

EXPOSE 8080

ENTRYPOINT ["bb", "prod"]
