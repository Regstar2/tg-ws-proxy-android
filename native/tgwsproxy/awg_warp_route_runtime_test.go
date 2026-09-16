package main

import "testing"

func TestAwgWarpOwnsRouteSelection(t *testing.T) {
	tests := []struct {
		name   string
		policy awgWarpRoutePolicy
		want   bool
	}{
		{
			name: "exclusive preferred AWG",
			policy: awgWarpRoutePolicy{
				Enabled:       true,
				Preferred:     true,
				AllowFallback: false,
			},
			want: true,
		},
		{
			name: "preferred AWG with fallback",
			policy: awgWarpRoutePolicy{
				Enabled:       true,
				Preferred:     true,
				AllowFallback: true,
			},
			want: false,
		},
		{
			name: "enabled but not preferred",
			policy: awgWarpRoutePolicy{
				Enabled:       true,
				Preferred:     false,
				AllowFallback: false,
			},
			want: false,
		},
		{
			name: "disabled",
			policy: awgWarpRoutePolicy{
				Enabled:       false,
				Preferred:     true,
				AllowFallback: false,
			},
			want: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := awgWarpOwnsRouteSelection(tt.policy); got != tt.want {
				t.Fatalf("awgWarpOwnsRouteSelection() = %t, want %t", got, tt.want)
			}
		})
	}
}
