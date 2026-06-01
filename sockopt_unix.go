//go:build !windows

package main

import "syscall"

func setSockoptInt(fd uintptr, level int, opt int, value int) error {
	return syscall.SetsockoptInt(int(fd), level, opt, value)
}
