package local

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"

	"github.com/hanger-source/agentic-olap/service/internal/auth"
	"github.com/hanger-source/agentic-olap/service/internal/config"
)

func Home(value string) (string, error) {
	if value == "" || value == "~" || strings.HasPrefix(value, "~/") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		switch {
		case value == "":
			value = filepath.Join(home, ".agentic-olap")
		case value == "~":
			value = home
		default:
			value = filepath.Join(home, value[2:])
		}
	}
	return filepath.Abs(value)
}
func writePrivate(path string, raw []byte, replace bool) error {
	file, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+"-")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	if err = file.Chmod(0600); err != nil {
		return err
	}
	if _, err = file.Write(raw); err != nil {
		return err
	}
	if err = file.Sync(); err != nil {
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	if replace {
		err = os.Rename(file.Name(), path)
	} else {
		err = os.Link(file.Name(), path)
	}
	if err != nil {
		return err
	}
	directory, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}
func Initialize(home, template string, port int) (string, config.Deployment, error) {
	path := filepath.Join(home, "server.json")
	if err := os.MkdirAll(home, 0700); err != nil {
		return path, config.Deployment{}, err
	}
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		c, err := config.Read(template)
		if err != nil {
			return path, c, err
		}
		operations := []string{}
		seen := map[string]bool{}
		for _, g := range c.Auth.Grants {
			for _, op := range g.Operations {
				if !seen[op] {
					seen[op] = true
					operations = append(operations, op)
				}
			}
		}
		if port == 0 {
			port = 8000
		}
		token := make([]byte, 32)
		if _, err = rand.Read(token); err != nil {
			return path, c, err
		}
		tokenFile := filepath.Join(home, "access-token")
		if err = writePrivate(tokenFile, []byte(base64.RawURLEncoding.EncodeToString(token)+"\n"), false); err != nil {
			return path, c, err
		}
		c.Listen = config.Listen{Host: "127.0.0.1", Port: port, AllowedHosts: []string{"127.0.0.1", "127.0.0.1:*", "localhost", "localhost:*"}, AllowedOrigins: []string{"http://127.0.0.1:*", "http://localhost:*"}}
		c.Auth = config.Auth{Mode: "local-token", TokenFile: tokenFile, Subject: "local", Scopes: []string{"olap.local"}, Grants: []config.Grant{{Subjects: []string{"local"}, Scopes: []string{"olap.local"}, Operations: operations, DataSources: []string{"*"}, SubjectAreas: []string{"*"}, Releases: []string{"*"}, AllGovernanceAssets: true}}}
		c.DataRoot = filepath.Join(home, "data")
		c.Connectors = []string{}
		c.DataSources = []json.RawMessage{}
		if err = c.Validate(); err != nil {
			return path, c, err
		}
		raw, err := json.MarshalIndent(c, "", "  ")
		if err != nil {
			return path, c, err
		}
		if err = writePrivate(path, append(raw, '\n'), false); err != nil {
			return path, c, err
		}
	} else if err != nil {
		return path, config.Deployment{}, err
	}
	path, c, err := Initialized(home)
	if err != nil {
		return path, c, err
	}
	if port != 0 && port != c.Listen.Port {
		c.Listen.Port = port
		if err = c.Validate(); err != nil {
			return path, c, err
		}
		raw, err := json.MarshalIndent(c, "", "  ")
		if err != nil {
			return path, c, err
		}
		if err = writePrivate(path, append(raw, '\n'), true); err != nil {
			return path, c, err
		}
	}
	return path, c, nil
}
func Initialized(home string) (string, config.Deployment, error) {
	path := filepath.Join(home, "server.json")
	c, err := config.Read(path)
	if err != nil {
		return path, c, err
	}
	if c.Auth.Mode != "local-token" {
		return path, c, errors.New("local home requires local-token; use start --config for external authentication")
	}
	_, err = auth.ReadLocalToken(c.Auth.TokenFile)
	return path, c, err
}
func Information(path string, c config.Deployment) map[string]any {
	var token any
	if c.Auth.Mode == "local-token" {
		token = c.Auth.TokenFile
	}
	return map[string]any{"url": c.URL(), "configPath": path, "dataRoot": c.DataRoot, "tokenPath": token, "authMode": c.Auth.Mode}
}
