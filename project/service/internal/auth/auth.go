package auth

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rsa"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"net/http"
	"os"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/hanger-source/agentic-olap/service/internal/config"
	"github.com/hanger-source/agentic-olap/service/internal/httpclient"
	"github.com/hanger-source/agentic-olap/service/internal/runtime"
)

var tokenPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{43,255}$`)

func ReadLocalToken(path string) (string, error) {
	fd, err := syscall.Open(path, syscall.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return "", err
	}
	f := os.NewFile(uintptr(fd), path)
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return "", err
	}
	if !st.Mode().IsRegular() || st.Mode().Perm() != 0600 {
		return "", errors.New("local token must be a private 0600 regular file")
	}
	raw, err := io.ReadAll(io.LimitReader(f, 257))
	if err != nil {
		return "", err
	}
	token := strings.TrimSuffix(string(raw), "\n")
	if len(raw) > 256 || !tokenPattern.MatchString(token) {
		return "", errors.New("invalid local token file")
	}
	return token, nil
}

type Verifier struct {
	config    config.Auth
	local     string
	client    *http.Client
	mu        sync.Mutex
	keys      map[string]key
	refreshed time.Time
}
type key struct {
	value     any
	algorithm string
}

func (v *Verifier) Close() { v.client.CloseIdleConnections() }
func New(c config.Auth) (*Verifier, error) {
	transport, err := httpclient.Transport()
	if err != nil {
		return nil, err
	}
	v := &Verifier{config: c, client: &http.Client{Transport: transport, Timeout: 5 * time.Second, CheckRedirect: func(request *http.Request, via []*http.Request) error {
		if request.URL.Scheme != "https" || request.URL.User != nil {
			return errors.New("JWKS redirect must remain HTTPS without credentials")
		}
		if len(via) >= 5 {
			return errors.New("too many JWKS redirects")
		}
		return nil
	}}, keys: map[string]key{}}
	if c.Mode == "local-token" {
		var err error
		v.local, err = ReadLocalToken(c.TokenFile)
		if err != nil {
			return nil, err
		}
	}
	return v, nil
}

type claims struct {
	jwt.RegisteredClaims
	Scope string `json:"scope"`
}

func (v *Verifier) Verify(ctx context.Context, bearer string) (runtime.Principal, error) {
	if v.config.Mode == "local-token" {
		if subtle.ConstantTimeCompare([]byte(bearer), []byte(v.local)) != 1 {
			return runtime.Principal{}, errors.New("invalid bearer")
		}
		return runtime.Principal{Subject: v.config.Subject, Scopes: append([]string{}, v.config.Scopes...)}, nil
	}
	var c claims
	token, err := jwt.ParseWithClaims(bearer, &c, func(t *jwt.Token) (any, error) {
		kid, ok := t.Header["kid"].(string)
		if !ok || kid == "" {
			return nil, errors.New("JWT key identity required")
		}
		return v.signingKey(ctx, kid, t.Method.Alg())
	}, jwt.WithValidMethods(v.config.Algorithms), jwt.WithIssuer(v.config.Issuer), jwt.WithAudience(v.config.Audience), jwt.WithExpirationRequired())
	if err != nil || !token.Valid || c.Subject == "" {
		return runtime.Principal{}, errors.New("invalid external bearer")
	}
	return runtime.Principal{Subject: c.Subject, Scopes: strings.Fields(c.Scope)}, nil
}
func (v *Verifier) signingKey(ctx context.Context, kid, algorithm string) (any, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if k, ok := v.keys[kid]; ok && time.Since(v.refreshed) < 5*time.Minute {
		if k.algorithm != "" && k.algorithm != algorithm {
			return nil, errors.New("JWT algorithm differs from JWK")
		}
		return k.value, nil
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, v.config.JWKSURI, nil)
	if err != nil {
		return nil, err
	}
	response, err := v.client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, errors.New("JWKS unavailable")
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, (1<<20)+1))
	if err != nil || len(raw) > 1<<20 {
		return nil, errors.New("JWKS exceeds budget")
	}
	var document struct {
		Keys []struct {
			Kty string `json:"kty"`
			Kid string `json:"kid"`
			Alg string `json:"alg"`
			Use string `json:"use"`
			N   string `json:"n"`
			E   string `json:"e"`
			Crv string `json:"crv"`
			X   string `json:"x"`
			Y   string `json:"y"`
		} `json:"keys"`
	}
	if json.Unmarshal(raw, &document) != nil {
		return nil, errors.New("invalid JWKS")
	}
	keys := map[string]key{}
	decode := base64.RawURLEncoding.DecodeString
	for _, j := range document.Keys {
		if j.Kid == "" || (j.Use != "" && j.Use != "sig") {
			continue
		}
		var value any
		switch j.Kty {
		case "RSA":
			n, e1 := decode(j.N)
			e, e2 := decode(j.E)
			if e1 != nil || e2 != nil || len(e) > 4 || len(e) == 0 || len(n) == 0 {
				continue
			}
			exponent := 0
			for _, b := range e {
				exponent = exponent<<8 | int(b)
			}
			if exponent < 3 {
				continue
			}
			value = &rsa.PublicKey{N: new(big.Int).SetBytes(n), E: exponent}
		case "EC":
			var curve elliptic.Curve
			switch j.Crv {
			case "P-256":
				curve = elliptic.P256()
			case "P-384":
				curve = elliptic.P384()
			default:
				continue
			}
			x, e1 := decode(j.X)
			y, e2 := decode(j.Y)
			if e1 != nil || e2 != nil {
				continue
			}
			px, py := new(big.Int).SetBytes(x), new(big.Int).SetBytes(y)
			if !curve.IsOnCurve(px, py) {
				continue
			}
			value = &ecdsa.PublicKey{Curve: curve, X: px, Y: py}
		default:
			continue
		}
		if _, duplicate := keys[j.Kid]; duplicate {
			return nil, errors.New("ambiguous JWKS key")
		}
		keys[j.Kid] = key{value, j.Alg}
	}
	v.keys = keys
	v.refreshed = time.Now()
	k, ok := keys[kid]
	if !ok || (k.algorithm != "" && k.algorithm != algorithm) {
		return nil, errors.New("JWT signing key unavailable")
	}
	return k.value, nil
}
