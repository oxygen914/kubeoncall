FROM python:3.12-alpine@sha256:6d43704baacd1bfbe7c295d7f13079d5d8104ed33568873133f8fc69980419df
RUN addgroup -S sandbox && adduser -S -G sandbox -u 65532 sandbox
COPY common/runner.py /usr/local/lib/koc/runner.py
COPY python/koc-run-python /usr/local/bin/koc-run-python
RUN chmod 0555 /usr/local/bin/koc-run-python /usr/local/lib/koc/runner.py
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/koc-run-python"]
