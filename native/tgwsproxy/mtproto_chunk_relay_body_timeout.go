package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"
)

const mtProtoChunkRelayDownBodyTimeout = 2500 * time.Millisecond

var errChunkRelayDownBodyTimeout = errors.New("chunk relay downstream body timeout")

type chunkRelayBodyReadResult struct {
	body []byte
	err  error
}

func readChunkRelayResponseBody(
	ctx context.Context,
	action string,
	resp *http.Response,
	maxBytes int64,
) ([]byte, error) {
	if resp == nil || resp.Body == nil {
		return nil, nil
	}
	timeout := time.Duration(0)
	if action == "down" && resp.StatusCode == http.StatusOK {
		timeout = mtProtoChunkRelayDownBodyTimeout
	}
	return readChunkRelayBodyWithTimeout(ctx, resp.Body, maxBytes, timeout)
}

func readChunkRelayBodyWithTimeout(
	ctx context.Context,
	body io.ReadCloser,
	maxBytes int64,
	timeout time.Duration,
) ([]byte, error) {
	if body == nil {
		return nil, nil
	}
	if ctx == nil {
		ctx = context.Background()
	}
	if timeout <= 0 {
		return io.ReadAll(io.LimitReader(body, maxBytes))
	}

	resultCh := make(chan chunkRelayBodyReadResult, 1)
	go func() {
		responseBody, err := io.ReadAll(io.LimitReader(body, maxBytes))
		resultCh <- chunkRelayBodyReadResult{body: responseBody, err: err}
	}()

	timer := time.NewTimer(timeout)
	defer timer.Stop()

	select {
	case result := <-resultCh:
		return result.body, result.err
	case <-ctx.Done():
		_ = body.Close()
		return nil, ctx.Err()
	case <-timer.C:
		_ = body.Close()
		return nil, fmt.Errorf("%w after %s: %w", errChunkRelayDownBodyTimeout, timeout, context.DeadlineExceeded)
	}
}

func chunkRelayDownBodyTimeoutError(err error) bool {
	return errors.Is(err, errChunkRelayDownBodyTimeout)
}
