package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"path/filepath"

	"github.com/hanger-source/agentic-olap/service/internal/wire"
)

type Listen struct {
	Host           string   `json:"host"`
	Port           int      `json:"port"`
	AllowedHosts   []string `json:"allowedHosts"`
	AllowedOrigins []string `json:"allowedOrigins"`
}
type Grant struct {
	Subjects            []string `json:"subjects"`
	Scopes              []string `json:"scopes"`
	Operations          []string `json:"operations"`
	DataSources         []string `json:"dataSources"`
	SubjectAreas        []string `json:"subjectAreas"`
	Releases            []string `json:"releases"`
	AllGovernanceAssets bool     `json:"allGovernanceAssets"`
}
type Auth struct {
	Mode       string   `json:"mode"`
	TokenFile  string   `json:"tokenFile,omitempty"`
	Subject    string   `json:"subject,omitempty"`
	Scopes     []string `json:"scopes,omitempty"`
	Issuer     string   `json:"issuer,omitempty"`
	Audience   string   `json:"audience,omitempty"`
	JWKSURI    string   `json:"jwksUri,omitempty"`
	Algorithms []string `json:"algorithms,omitempty"`
	Grants     []Grant  `json:"grants"`
}
type Limits struct {
	RequestSeconds          float64 `json:"requestSeconds"`
	StartupSeconds          float64 `json:"startupSeconds"`
	MaxInFlight             int     `json:"maxInFlight"`
	WorkerCount             int     `json:"workerCount"`
	CatalogEntriesPerWorker int     `json:"catalogEntriesPerWorker"`
	RequestBytes            int     `json:"requestBytes"`
	InputDepth              int     `json:"inputDepth"`
	InputNodes              int     `json:"inputNodes"`
	InputFileBytes          int     `json:"inputFileBytes"`
	InputFiles              int     `json:"inputFiles"`
	ResponseBytes           int     `json:"responseBytes"`
	PendingOutputBytes      int     `json:"pendingOutputBytes"`
	WorkerJvmHeapMiB        int     `json:"workerJvmHeapMiB"`
	NativeMemoryBytes       int64   `json:"nativeMemoryBytes"`
	CatalogBytes            int     `json:"catalogBytes"`
	SQLBytes                int     `json:"sqlBytes"`
	Parameters              int     `json:"parameters"`
	ResultRows              int     `json:"resultRows"`
	ResultValues            int     `json:"resultValues"`
	ResultBytes             int     `json:"resultBytes"`
	ResultDepth             int     `json:"resultDepth"`
}
type History struct {
	Enabled       bool `json:"enabled"`
	RetentionDays int  `json:"retentionDays"`
}
type Scripting struct {
	WorkerCount    int   `json:"workerCount"`
	JVMHeapMiB     int   `json:"jvmHeapMiB"`
	MaxCalls       int   `json:"maxCalls"`
	NativeLogBytes int64 `json:"nativeLogBytes"`
}

func (s Scripting) Validate() error {
	if s.WorkerCount < 1 || s.WorkerCount > 64 || s.JVMHeapMiB < 128 || s.MaxCalls < 1 || s.MaxCalls > 10000 || s.NativeLogBytes < 1024 {
		return errors.New("invalid script process or capability call budgets")
	}
	return nil
}

type Deployment struct {
	Listen      Listen            `json:"listen"`
	Auth        Auth              `json:"auth"`
	DataRoot    string            `json:"dataRoot"`
	Connectors  []string          `json:"connectors"`
	DataSources []json.RawMessage `json:"dataSources"`
	Limits      Limits            `json:"limits"`
	History     History           `json:"history"`
	Scripting   Scripting         `json:"scripting"`
}

func Read(path string) (Deployment, error) {
	var c Deployment
	file, err := os.Open(path)
	if err != nil {
		return c, err
	}
	defer file.Close()
	raw, err := io.ReadAll(io.LimitReader(file, (4<<20)+1))
	if err != nil {
		return c, err
	}
	if len(raw) > 4<<20 {
		return c, errors.New("server configuration exceeds 4 MiB")
	}
	root, err := wire.Object(raw, 512, 1000000)
	if err != nil {
		return c, err
	}
	for _, field := range []string{"listen", "auth", "dataRoot", "connectors", "dataSources", "limits", "history", "scripting"} {
		if root[field] == nil {
			return c, fmt.Errorf("configuration requires %s", field)
		}
	}
	if _, ok := root["connectors"].([]any); !ok {
		return c, errors.New("connectors must be an explicit array")
	}
	if _, ok := root["dataSources"].([]any); !ok {
		return c, errors.New("dataSources must be an explicit array")
	}
	history, ok := root["history"].(map[string]any)
	if !ok {
		return c, errors.New("history must be explicit")
	}
	if _, ok = history["enabled"].(bool); !ok {
		return c, errors.New("history.enabled must be an explicit boolean")
	}
	auth, ok := root["auth"].(map[string]any)
	if !ok {
		return c, errors.New("auth must be explicit")
	}
	if _, ok = auth["grants"].([]any); !ok {
		return c, errors.New("auth.grants must be an explicit array")
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.DisallowUnknownFields()
	if err = d.Decode(&c); err != nil {
		return c, err
	}
	if err = c.Validate(); err != nil {
		return c, err
	}
	base, err := filepath.Abs(filepath.Dir(path))
	if err != nil {
		return c, err
	}
	absolute := func(p string) string {
		if filepath.IsAbs(p) {
			return filepath.Clean(p)
		}
		return filepath.Join(base, p)
	}
	c.DataRoot = absolute(c.DataRoot)
	for i, p := range c.Connectors {
		c.Connectors[i] = absolute(p)
	}
	if c.Auth.Mode == "local-token" {
		c.Auth.TokenFile = absolute(c.Auth.TokenFile)
	}
	return c, nil
}
func (c Deployment) Validate() error {
	if c.Listen.Host == "" || c.Listen.Port < 1 || c.Listen.Port > 65535 || len(c.Listen.AllowedHosts) == 0 || c.DataRoot == "" {
		return errors.New("explicit listen, allowed hosts and dataRoot are required")
	}
	if c.Auth.Mode == "local-token" {
		if c.Listen.Host != "127.0.0.1" && c.Listen.Host != "::1" {
			return errors.New("local token requires a loopback listener")
		}
		if c.Auth.TokenFile == "" || c.Auth.Subject == "" || len(c.Auth.Scopes) == 0 || c.Auth.Issuer != "" || c.Auth.JWKSURI != "" || c.Auth.Audience != "" || len(c.Auth.Algorithms) > 0 {
			return errors.New("invalid local-token configuration")
		}
	} else if c.Auth.Mode == "external-jwt" {
		if c.Auth.TokenFile != "" || c.Auth.Subject != "" || len(c.Auth.Scopes) > 0 {
			return errors.New("invalid external-jwt configuration")
		}
		for _, s := range []string{c.Auth.Issuer, c.Auth.Audience, c.Auth.JWKSURI} {
			u, e := url.Parse(s)
			if e != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" {
				return errors.New("authentication endpoints require HTTPS without embedded credentials")
			}
		}
		if len(c.Auth.Algorithms) == 0 {
			return errors.New("JWT algorithms required")
		}
		for _, a := range c.Auth.Algorithms {
			switch a {
			case "RS256", "RS384", "RS512", "ES256", "ES384":
			default:
				return errors.New("unsupported JWT algorithm")
			}
		}
	} else {
		return errors.New("explicit authentication mode required")
	}
	for _, g := range c.Auth.Grants {
		if len(g.Subjects) == 0 || len(g.Scopes) == 0 || len(g.Operations) == 0 {
			return errors.New("grant requires subjects, scopes and operations")
		}
	}
	l := c.Limits
	if err := c.Scripting.Validate(); err != nil {
		return err
	}
	if l.RequestSeconds <= 0 || l.RequestSeconds > 3600 || l.StartupSeconds <= 0 || l.StartupSeconds > 3600 || l.MaxInFlight < 1 || l.MaxInFlight > 256 || l.WorkerCount < 1 || l.WorkerCount > 64 || l.CatalogEntriesPerWorker < 1 || l.CatalogEntriesPerWorker > 256 || l.InputDepth < 1 || l.InputDepth > 512 || l.InputNodes < 1 || l.InputFiles < 1 || l.InputFileBytes < 1024 || l.RequestBytes < 1024 || l.ResponseBytes < 1024 || l.PendingOutputBytes < l.ResponseBytes || l.WorkerJvmHeapMiB < 32 || l.NativeMemoryBytes < 1 || l.CatalogBytes < 1 || l.SQLBytes < 1 || l.Parameters < 1 || l.ResultRows < 1 || l.ResultValues < 1 || l.ResultBytes < 1 || l.ResultDepth < 1 || c.History.RetentionDays < 1 {
		return errors.New("invalid deployment resource budgets")
	}
	if len(c.DataSources) > 0 && len(c.Connectors) == 0 {
		return errors.New("sources require exact connector deployments")
	}
	for _, raw := range c.DataSources {
		var source struct {
			ID string `json:"dataSourceId"`
		}
		if json.Unmarshal(raw, &source) != nil || source.ID == "" {
			return errors.New("source requires dataSourceId")
		}
	}
	return nil
}
func (c Deployment) URL() string {
	return "http://" + net.JoinHostPort(c.Listen.Host, fmt.Sprint(c.Listen.Port)) + "/mcp"
}
func (c Deployment) JSON() ([]byte, error) { return json.Marshal(c) }
