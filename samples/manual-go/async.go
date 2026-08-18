package main

// Manual sandbox root for Go closure bodies and their timing (V0.5_SPEC.md
// cases H and I). Put the caret in serveRequest() and run Analyze Flow.
//
// Go is the one language that states the timing itself: `go` and `defer` are
// keywords, so nothing has to be looked up or guessed.
//
// Expected shape on the canvas:
//
//	acquire()
//	↩ { } → func()               I: deferred — it runs when serveRequest() returns,
//	    release()                    not here, so the connector is dashed
//	⚡ { } → func()               H: goroutine — it may run at any time
//	    charge()                     and its body is visible all the same
//	handOff()
//	⧖ { } → handOff()            not a keyword and not a documented API, so
//	    tidy()                    the timing is not determined
//	respond()                    the next synchronous step
//
// Three things to check:
//
//  1. release(), charge(), and tidy() must NOT appear in serveRequest()'s own
//     sequence. Each belongs to the closure it is written in.
//  2. respond() must have a solid connector: it is what runs next.
//  3. The deferred card must not look like an ordinary immediate call. A defer
//     that reads as "runs here" is worse than no card at all.
//
// Then analyze kept(): the closure is assigned to a variable and never handed
// to a call, so there is no callback card (KNOWN_LIMITATIONS.md §42).
func serveRequest() {
	acquire()
	defer func() {
		release()
	}()
	go func() {
		charge()
	}()
	handOff(func() {
		tidy()
	})
	respond()
}

// The closure's invocation site is elsewhere, so nothing is invented for it.
func kept() {
	f := func() {
		charge()
	}
	use(f)
	respond()
}

func handOff(f func()) {}
func use(f func())     {}

func acquire() {}
func release() {}
func charge()  {}
func tidy()    {}
func respond() {}
