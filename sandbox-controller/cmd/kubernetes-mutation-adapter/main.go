package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/kubeoncall/sandbox-controller/internal/kubetooladapter"
	k8sclient "k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
)

func main() {
	config, err := kubetooladapter.LoadMutationConfigFromEnv()
	if err != nil {
		slog.Error("invalid Kubernetes mutation adapter configuration", "error", err)
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
	ledger := kubetooladapter.NewKubernetesOperationLedger(clientset, config.LedgerNamespace)
	mutator := kubetooladapter.NewKubernetesMutator(clientset, config, ledger)
	server := kubetooladapter.NewMutationServer(config, mutator)
	httpServer := &http.Server{
		Addr:              config.ListenAddress,
		Handler:           server.Handler(),
		ReadHeaderTimeout: config.RequestTimeout,
		ReadTimeout:       config.RequestTimeout,
		WriteTimeout:      config.RequestTimeout,
		IdleTimeout:       config.RequestTimeout * 6,
	}
	signalContext, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	go func() {
		slog.Info(
			"governed Kubernetes mutation adapter listening",
			"address",
			config.ListenAddress,
			"cluster",
			config.ClusterID,
		)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("Kubernetes mutation adapter stopped unexpectedly", "error", err)
			os.Exit(1)
		}
	}()
	go func() {
		ticker := time.NewTicker(config.LedgerCleanupInterval)
		defer ticker.Stop()
		for {
			select {
			case <-signalContext.Done():
				return
			case <-ticker.C:
				deleted, err := ledger.Prune(
					signalContext,
					time.Now().UTC().Add(-config.LedgerRetention),
				)
				if err != nil {
					slog.Warn("operation ledger cleanup failed", "errorType", fmt.Sprintf("%T", err))
					continue
				}
				if deleted > 0 {
					slog.Info("expired operation ledger records removed", "count", deleted)
				}
			}
		}
	}()

	<-signalContext.Done()
	shutdownContext, cancel := context.WithTimeout(context.Background(), config.ShutdownTimeout)
	defer cancel()
	if err := httpServer.Shutdown(shutdownContext); err != nil {
		slog.Error("Kubernetes mutation adapter shutdown failed", "error", err)
		os.Exit(1)
	}
}
