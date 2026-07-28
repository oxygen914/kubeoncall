FROM alpine/helm:3.16.3@sha256:ed9dfc49d43d034df3f9880eb777caf0183e5156508672478b80412c63f3db4f AS helm
FROM ghcr.io/yannh/kubeconform:v0.6.7@sha256:0925177fb05b44ce18574076141b5c3d83235e1904d3f952182ac99ddc45762c AS kubeconform
FROM openpolicyagent/conftest:v0.56.0@sha256:9204b38318a7b4be4e49403d81a6c53ed81a99d0c3dd770a216a65edb9a412c7 AS conftest
FROM python:3.12-alpine@sha256:6d43704baacd1bfbe7c295d7f13079d5d8104ed33568873133f8fc69980419df
RUN addgroup -S sandbox && adduser -S -G sandbox -u 65532 sandbox && apk add --no-cache curl
COPY --from=helm /usr/bin/helm /usr/local/bin/helm
COPY --from=kubeconform /kubeconform /usr/local/bin/kubeconform
COPY --from=conftest /conftest /usr/local/bin/conftest
COPY koc-validate-manifest /usr/local/bin/koc-validate-manifest
RUN chmod 0555 /usr/local/bin/koc-validate-manifest /usr/local/bin/helm /usr/local/bin/kubeconform /usr/local/bin/conftest
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/koc-validate-manifest"]
