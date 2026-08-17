package main

// Manual sandbox root for Go (acceptance Q, R). Put the caret in run() and
// run Analyze Flow.
//
// Expected order: load, convert, save, notify (goroutine badge),
// produce (sync), cleanup (deferred badge), Server.Start.
func run(s *Server) {
	save(convert(load()))
	go notify()
	defer cleanup(produce())
	s.Start()
}

func load() string    { return "" }
func convert(s string) string { return s }
func save(s string)   { audit() }
func audit()          {}
func notify()         {}
func produce() int    { return 1 }
func cleanup(x int)   {}

type Server struct{}

// Receiver method target; expandable.
func (s *Server) Start() {
	notify()
}
