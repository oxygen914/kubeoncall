FROM alpine/helm:3.16.3 AS helm
FROM ghcr.io/yannh/kubeconform:v0.6.7 AS kubeconform
FROM openpolicyagent/conftest:v0.56.0 AS conftest
FROM python:3.12-alpine
RUN addgroup -S sandbox && adduser -S -G sandbox -u 65532 sandbox && apk add --no-cache curl
COPY --from=helm /usr/bin/helm /usr/local/bin/helm
COPY --from=kubeconform /kubeconform /usr/local/bin/kubeconform
COPY --from=conftest /conftest /usr/local/bin/conftest
COPY koc-validate-manifest /usr/local/bin/koc-validate-manifest
RUN chmod 0555 /usr/local/bin/koc-validate-manifest /usr/local/bin/helm /usr/local/bin/kubeconform /usr/local/bin/conftest
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/koc-validate-manifest"]
