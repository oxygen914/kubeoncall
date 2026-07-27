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
	"github.com/kubeoncall/sandbox-controller/internal/jobs"
	sbxk8s "github.com/kubeoncall/sandbox-controller/internal/kubernetes"

	k8sclient "k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
)

func main() {
	config, err := httpapi.LoadConfigFromEnv()
	if err != nil {
		slog.Error("invalid sandbox controller configuration", "error", err)
		os.Exit(1)
	}
	manager := buildLifecycleManager(config)
	server := httpapi.NewServerWithManager(config, manager)
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

// buildLifecycleManager constructs the Kubernetes Job manager when an in-cluster config is present.
// When KUBERNETUS_SERVICE_HOST is unset (no in-cluster config) the controller starts in scaffold
// mode with a nil manager: lifecycle endpoints report CONTROLLER_NOT_READY, but health and auth
// still serve. This keeps the image deployable without cluster credentials during rollout.
func buildLifecycleManager(config httpapi.Config) httpapi.LifecycleManager {
	restConfig, err := rest.InClusterConfig()
	if err != nil {
		slog.Warn("sandbox controller starting without in-cluster config; lifecycle endpoints disabled", "error", err)
		return nil
	}
	clientset, err := k8sclient.NewForConfig(restConfig)
	if err != nil {
		slog.Error("cannot build kubernetes clientset", "error", err)
		return nil
	}
	builder := jobs.Builder{
		Namespace: config.SandboxNamespace,
		Tools:     config.Tools,
		Ceiling:   config.JobCeiling,
	}
	return sbxk8s.NewHTTPAdapter(sbxk8s.NewManager(clientset, config.SandboxNamespace, builder))
}
