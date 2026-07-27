package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/kubeoncall/sandbox-controller/internal/httpapi"
)

func main() {
	config, err := httpapi.LoadConfigFromEnv()
	if err != nil {
		slog.Error("invalid sandbox controller configuration", "error", err)
		os.Exit(1)
	}
	server := httpapi.NewServer(config)
	httpServer := &http.Server{
		Addr:              config.ListenAddress,
		Handler:           server.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       config.RequestTimeout,
		WriteTimeout:      config.RequestTimeout,
		IdleTimeout:       60 * time.Second,
	}

	go func() {
		slog.Info("sandbox controller listening", "address", config.ListenAddress)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("sandbox controller stopped unexpectedly", "error", err)
			os.Exit(1)
		}
	}()

	signalContext, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	<-signalContext.Done()
	shutdownContext, cancel := context.WithTimeout(context.Background(), config.ShutdownTimeout)
	defer cancel()
	if err := httpServer.Shutdown(shutdownContext); err != nil {
		slog.Error("sandbox controller graceful shutdown failed", "error", err)
		os.Exit(1)
	}
}
