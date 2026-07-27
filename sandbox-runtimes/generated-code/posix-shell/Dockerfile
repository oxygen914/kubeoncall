FROM python:3.12-alpine
RUN addgroup -S sandbox && adduser -S -G sandbox -u 65532 sandbox
COPY common/runner.py /usr/local/lib/koc/runner.py
COPY posix-shell/koc-run-posix-shell /usr/local/bin/koc-run-posix-shell
RUN chmod 0555 /usr/local/bin/koc-run-posix-shell /usr/local/lib/koc/runner.py
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/koc-run-posix-shell"]
