package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/kubeoncall/sandbox-controller/internal/kubetooladapter"
	k8sclient "k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
)

func main() {
	config, err := kubetooladapter.LoadConfigFromEnv()
	if err != nil {
		slog.Error("invalid Kubernetes tool adapter configuration", "error", err)
		os.Exit(1)
	}
	restConfig, err := rest.InClusterConfig()
	if err != nil {
		slog.Error("in-cluster Kubernetes configuration is required", "error", err)
		os.Exit(1)
	}
	clientset, err := k8sclient.NewForConfig(restConfig)
	if err != nil {
		slog.Error("cannot create Kubernetes client", "error", err)
		os.Exit(1)
	}
	reader := kubetooladapter.NewKubernetesReader(clientset, config)
	server := kubetooladapter.NewServer(config, reader)
	httpServer := &http.Server{
		Addr:              config.ListenAddress,
		Handler:           server.Handler(),
		ReadHeaderTimeout: config.RequestTimeout,
		ReadTimeout:       config.RequestTimeout,
		WriteTimeout:      config.RequestTimeout,
		IdleTimeout:       config.RequestTimeout * 6,
	}

	go func() {
		slog.Info("read-only Kubernetes tool adapter listening", "address", config.ListenAddress, "cluster", config.ClusterID)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("Kubernetes tool adapter stopped unexpectedly", "error", err)
			os.Exit(1)
		}
	}()

	signalContext, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	<-signalContext.Done()
	shutdownContext, cancel := context.WithTimeout(context.Background(), config.ShutdownTimeout)
	defer cancel()
	if err := httpServer.Shutdown(shutdownContext); err != nil {
		slog.Error("Kubernetes tool adapter shutdown failed", "error", err)
		os.Exit(1)
	}
}
