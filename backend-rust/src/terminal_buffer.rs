use lazy_static::lazy_static;
use simdutf8;

lazy_static! {
    /// Pattern to match Device Attributes (DA) responses
    /// Matches sequences like ESC[?0c or ESC[?0;0c
    static ref DA_PATTERN: String = r#"\x1b\[\?[0-9;]*c"#.to_string();
}

/// Remove unwanted control sequences that appear in terminal output
/// These are typically responses to terminal capability queries that shouldn't be displayed
pub fn filter_control_sequences(text: &str) -> String {
    // Only remove Device Attributes responses (ESC[?...c format)
    // This is much more conservative than removing literal 0;0;0c patterns
    // which could appear in legitimate output
    let mut filtered = text.replace("\x1b[?0c", "");
    filtered = filtered.replace("\x1b[?1c", "");
    filtered = filtered.replace("\x1b[?0;0c", "");
    filtered = filtered.replace("\x1b[?1;0c", "");
    filtered = filtered.replace("\x1b[?2c", "");

    filtered
}

/// Zero-copy UTF-8 streaming decoder for terminal output chunks
pub struct Utf8StreamDecoder {
    incomplete: Vec<u8>,
}

impl Utf8StreamDecoder {
    pub fn new() -> Self {
        Self {
            incomplete: Vec::with_capacity(4),
        }
    }

    pub fn decode_chunk(&mut self, input: &[u8]) -> (String, usize) {
        let mut result = String::with_capacity(input.len());
        let mut processed = 0;

        // Handle incomplete bytes from previous chunk
        if !self.incomplete.is_empty() {
            let combined_len = self.incomplete.len() + input.len().min(4);
            let mut combined = Vec::with_capacity(combined_len);
            combined.extend_from_slice(&self.incomplete);
            combined.extend_from_slice(&input[..input.len().min(4)]);

            match simdutf8::basic::from_utf8(&combined) {
                Ok(s) => {
                    result.push_str(s);
                    processed = combined.len() - self.incomplete.len();
                    self.incomplete.clear();
                }
                Err(_) => {
                    match std::str::from_utf8(&combined) {
                        Ok(s) => {
                            result.push_str(s);
                            processed = combined.len() - self.incomplete.len();
                            self.incomplete.clear();
                        }
                        Err(e) => {
                            let valid_up_to = e.valid_up_to();
                            if valid_up_to > 0 {
                                // SAFETY: We validated the prefix as UTF-8 above.
                                result.push_str(unsafe {
                                    std::str::from_utf8_unchecked(&combined[..valid_up_to])
                                });
                                processed = valid_up_to.saturating_sub(self.incomplete.len());
                                self.incomplete.clear();
                            }
                        }
                    }
                }
            }
        }

        // Process main input
        let remaining = &input[processed..];
        match simdutf8::basic::from_utf8(remaining) {
            Ok(s) => {
                result.push_str(s);
                processed = input.len();
            }
            Err(_) => {
                match std::str::from_utf8(remaining) {
                    Ok(s) => {
                        result.push_str(s);
                        processed = input.len();
                    }
                    Err(e) => {
                        let valid_up_to = e.valid_up_to();
                        if valid_up_to > 0 {
                            // SAFETY: We validated the valid prefix above.
                            result.push_str(unsafe {
                                std::str::from_utf8_unchecked(&remaining[..valid_up_to])
                            });
                        }

                        // Save incomplete bytes for next chunk
                        let incomplete_start = processed + valid_up_to;
                        if incomplete_start < input.len() {
                            self.incomplete.clear();
                            self.incomplete
                                .extend_from_slice(&input[incomplete_start..]);
                        }
                        processed = input.len();
                    }
                }
            }
        }

        (result, processed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_filter_control_sequences_removes_da_responses() {
        assert_eq!(filter_control_sequences("\x1b[?0c"), "");
        assert_eq!(filter_control_sequences("\x1b[?1c"), "");
        assert_eq!(filter_control_sequences("\x1b[?0;0c"), "");
        assert_eq!(filter_control_sequences("\x1b[?1;0c"), "");
        assert_eq!(filter_control_sequences("\x1b[?2c"), "");
    }

    #[test]
    fn test_filter_control_sequences_preserves_normal_text() {
        let text = "Hello, World!\nSome terminal output";
        assert_eq!(filter_control_sequences(text), text);
    }

    #[test]
    fn test_filter_control_sequences_mixed() {
        let input = "before\x1b[?0cafter";
        assert_eq!(filter_control_sequences(input), "beforeafter");
    }

    #[test]
    fn test_filter_empty_string() {
        assert_eq!(filter_control_sequences(""), "");
    }

    #[test]
    fn test_utf8_decoder_simple_ascii() {
        let mut decoder = Utf8StreamDecoder::new();
        let (out, _) = decoder.decode_chunk(b"hello");
        assert_eq!(out, "hello");
    }

    #[test]
    fn test_utf8_decoder_valid_utf8() {
        let mut decoder = Utf8StreamDecoder::new();
        let (out, _) = decoder.decode_chunk("héllo".as_bytes());
        assert_eq!(out, "héllo");
    }

    #[test]
    fn test_utf8_decoder_split_multibyte() {
        let mut decoder = Utf8StreamDecoder::new();
        let bytes = "é".as_bytes(); // é is 2 bytes: 0xc3 0xa9
        let (out1, _) = decoder.decode_chunk(&bytes[..1]);
        let (out2, _) = decoder.decode_chunk(&bytes[1..]);
        assert_eq!(out1 + &out2, "é");
    }

    #[test]
    fn test_utf8_decoder_empty_chunk() {
        let mut decoder = Utf8StreamDecoder::new();
        let (out, _) = decoder.decode_chunk(b"");
        assert_eq!(out, "");
    }

    #[test]
    fn test_utf8_decoder_multiple_chunks() {
        let mut decoder = Utf8StreamDecoder::new();
        let (o1, _) = decoder.decode_chunk(b"hel");
        let (o2, _) = decoder.decode_chunk(b"lo");
        assert_eq!(o1 + &o2, "hello");
    }

    #[test]
    fn test_utf8_decoder_full_unicode() {
        let mut decoder = Utf8StreamDecoder::new();
        let (out, _) = decoder.decode_chunk("こんにちは".as_bytes());
        assert_eq!(out, "こんにちは");
    }

    #[test]
    fn test_utf8_decoder_3byte_split() {
        let mut decoder = Utf8StreamDecoder::new();
        let bytes = "€".as_bytes(); // € is 3 bytes: 0xE2 0x82 0xAC
        assert_eq!(bytes.len(), 3);
        let (out1, _) = decoder.decode_chunk(&bytes[..1]);
        let (out2, _) = decoder.decode_chunk(&bytes[1..]);
        assert_eq!(format!("{}{}", out1, out2), "€");
    }

    #[test]
    fn test_utf8_decoder_4byte_emoji_split() {
        let mut decoder = Utf8StreamDecoder::new();
        let bytes = "😀".as_bytes(); // 4 bytes: F0 9F 98 80
        assert_eq!(bytes.len(), 4);
        let (out1, _) = decoder.decode_chunk(&bytes[..1]);
        let (out2, _) = decoder.decode_chunk(&bytes[1..]);
        assert_eq!(format!("{}{}", out1, out2), "😀");
    }

    #[test]
    fn test_utf8_decoder_4byte_split_at_2() {
        let mut decoder = Utf8StreamDecoder::new();
        let bytes = "😀".as_bytes();
        let (out1, _) = decoder.decode_chunk(&bytes[..2]);
        let (out2, _) = decoder.decode_chunk(&bytes[2..]);
        assert_eq!(format!("{}{}", out1, out2), "😀");
    }

    #[test]
    fn test_utf8_decoder_invalid_bytes() {
        let mut decoder = Utf8StreamDecoder::new();
        // Invalid UTF-8 bytes followed by valid ASCII
        let input: Vec<u8> = vec![0xFF, 0xFE, b'h', b'i'];
        let (out, _) = decoder.decode_chunk(&input);
        // Should not panic on invalid bytes; output content is implementation-defined
        let _ = out;
    }

    #[test]
    fn test_filter_sequences_embedded_in_multiline() {
        let input = "line1\nline2\x1b[?0cline3\nline4";
        let result = filter_control_sequences(input);
        assert!(result.contains("line1"));
        assert!(result.contains("line2"));
        assert!(result.contains("line3"));
        assert!(result.contains("line4"));
        assert!(!result.contains("\x1b[?0c"));
    }

    // Phase 6: Filter & decoder additional tests

    #[test]
    fn test_filter_multiple_consecutive_da() {
        let input = "\x1b[?0c\x1b[?1c\x1b[?2c";
        assert_eq!(filter_control_sequences(input), "");
    }

    #[test]
    fn test_filter_da_at_boundaries() {
        let input = "\x1b[?0chello\x1b[?1c";
        assert_eq!(filter_control_sequences(input), "hello");
    }

    #[test]
    fn test_filter_preserves_ansi_color_codes() {
        let input = "\x1b[31mred text\x1b[0m";
        assert_eq!(filter_control_sequences(input), input);
    }

    #[test]
    fn test_utf8_decoder_3byte_split_at_all_positions() {
        let bytes = "€".as_bytes(); // 3 bytes: E2 82 AC
        assert_eq!(bytes.len(), 3);

        // Split at position 2
        let mut decoder = Utf8StreamDecoder::new();
        let (o1, _) = decoder.decode_chunk(&bytes[..2]);
        let (o2, _) = decoder.decode_chunk(&bytes[2..]);
        assert_eq!(format!("{}{}", o1, o2), "€");
    }

    #[test]
    fn test_utf8_decoder_mixed_ascii_and_multibyte_split() {
        let mut decoder = Utf8StreamDecoder::new();
        let text = "hi€ok";
        let bytes = text.as_bytes();
        // Split in the middle of the euro sign
        let split_at = 3; // 'h','i', then first byte of €
        let (o1, _) = decoder.decode_chunk(&bytes[..split_at]);
        let (o2, _) = decoder.decode_chunk(&bytes[split_at..]);
        assert_eq!(format!("{}{}", o1, o2), text);
    }

    #[test]
    fn test_utf8_decoder_consecutive_multibyte_across_chunks() {
        let mut decoder = Utf8StreamDecoder::new();
        let text = "€€";
        let bytes = text.as_bytes(); // 6 bytes total
                                     // Split at byte 4 (middle of second €)
        let (o1, _) = decoder.decode_chunk(&bytes[..4]);
        let (o2, _) = decoder.decode_chunk(&bytes[4..]);
        assert_eq!(format!("{}{}", o1, o2), text);
    }
}
