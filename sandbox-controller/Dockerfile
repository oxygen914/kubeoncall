FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /out/sandbox-controller ./cmd/sandbox-controller

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/sandbox-controller /sandbox-controller
USER 65532:65532
EXPOSE 8088
ENTRYPOINT ["/sandbox-controller"]
