package responses

import (
	"errors"
	"io"
	"unicode/utf8"
)

type Page struct {
	Reference  Reference `json:"reference"`
	Content    string    `json:"content"`
	Offset     int64     `json:"offset"`
	NextOffset *int64    `json:"nextOffset"`
	TotalBytes int64     `json:"totalBytes"`
}

// Read returns an exact UTF-8 byte range. The immutable archive is never
// truncated, and a caller can concatenate pages and verify the full SHA-256.
func (s Store) Read(ref Reference, offset, length int64) (Page, error) {
	if offset < 0 || length < 1 {
		return Page{}, errors.New("response offset must be nonnegative and length must be positive")
	}
	metadata, err := s.Metadata(ref)
	if err != nil {
		return Page{}, err
	}
	location, err := s.Location(ref)
	if err != nil {
		return Page{}, err
	}
	file, err := openRegular(location)
	if err != nil {
		return Page{}, err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return Page{}, err
	}
	if info.Size() != metadata.Bytes {
		return Page{}, errors.New("saved response byte count differs from its committed metadata")
	}
	if offset > metadata.Bytes {
		return Page{}, errors.New("response offset exceeds the complete saved response")
	}
	page := Page{Reference: ref, Offset: offset, TotalBytes: metadata.Bytes}
	if offset == metadata.Bytes {
		return page, nil
	}
	var first [1]byte
	if _, err := file.ReadAt(first[:], offset); err != nil {
		return Page{}, err
	}
	if !utf8.RuneStart(first[0]) {
		return Page{}, errors.New("response offset must be at a UTF-8 boundary; use the previous page's nextOffset")
	}
	length = min(length, metadata.Bytes-offset)
	if length > int64(int(^uint(0)>>1)) {
		return Page{}, errors.New("response range exceeds this process's addressable buffer length")
	}
	content := make([]byte, int(length))
	if _, err := io.ReadFull(io.NewSectionReader(file, offset, length), content); err != nil {
		return Page{}, err
	}
	end := len(content)
	for removed := 0; !utf8.Valid(content[:end]); removed++ {
		if removed == utf8.UTFMax-1 || end == 0 {
			return Page{}, errors.New("saved response range is not valid UTF-8")
		}
		end--
	}
	if end == 0 {
		return Page{}, errors.New("response length must fit a complete UTF-8 character at the requested offset")
	}
	page.Content = string(content[:end])
	if next := offset + int64(end); next < metadata.Bytes {
		page.NextOffset = &next
	}
	return page, nil
}
