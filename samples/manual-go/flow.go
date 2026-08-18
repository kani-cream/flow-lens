package main

// Manual sandbox root for Go control flow (V0.2_SPEC.md cases K, L, M).
// Put the caret in dispatch() and run Analyze Flow.
//
// Expected shape on the canvas:
//
//	lookup()                     K: the switch init runs before the container
//	◈ switch mode                the title names the subject, not the init: the
//	    CASE 1       fast()          init already has a card of its own
//	    DEFAULT      slow()
//	◈ select                     L: the card must say "select", not "switch"
//	    CASE v := <-ready()      the label's call is on the map, not just in the
//	        ready()                  label text, and it runs inside the case
//	        consume(v)
//	    DEFAULT      idle()
//	◈ switch                     a tagless switch: the case label IS the
//	    CASE healthy()               condition, so it runs inside its own section
//	        healthy()
//	        serve()
//	    DEFAULT      degrade()
//	queue()                      the range expression runs once, before the loop
//	↻ loop _, job := range queue()
//	    EACH ITERATION           containers nest
//	        accepted(job)        the nested condition runs first
//	        ◆ if accepted(job)
//	            THEN  handle(job)
//	◀ return                     a terminal marker, not a call card
//
// The status bar must NOT warn: every construct here is represented.
func dispatch() {
	switch mode := lookup(); mode {
	case 1:
		fast()
	default:
		slow()
	}

	select {
	case v := <-ready():
		consume(v)
	default:
		idle()
	}

	switch {
	case healthy():
		serve()
	default:
		degrade()
	}

	for _, job := range queue() {
		if accepted(job) {
			handle(job)
		}
	}

	return
}

// The loop condition repeats, so it belongs inside the container. Analyze
// retryLoop() and check that the card reads "↻ loop keepGoing()" with both
// keepGoing() and attempt() inside EACH ITERATION, once each.
func retryLoop() {
	for keepGoing() {
		attempt()
	}
	finish()
}

func lookup() int          { return 1 }
func ready() chan int      { return nil }
func healthy() bool        { return true }
func queue() []string      { return nil }
func accepted(job string) bool { return true }
func keepGoing() bool      { return false }

func fast()             {}
func slow()             {}
func consume(v int)     {}
func idle()             {}
func serve()            {}
func degrade()          {}
func handle(job string) {}
func attempt()          {}
func finish()           {}
