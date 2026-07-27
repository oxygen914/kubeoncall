FROM python:3.12-alpine
RUN addgroup -S sandbox && adduser -S -G sandbox -u 65532 sandbox
COPY common/runner.py /usr/local/lib/koc/runner.py
COPY python/koc-run-python /usr/local/bin/koc-run-python
RUN chmod 0555 /usr/local/bin/koc-run-python /usr/local/lib/koc/runner.py
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/koc-run-python"]
