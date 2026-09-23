//! Native, thread-safe document engine exposed to Android through a narrow JNI ABI.
//! The public JSON protocol keeps Kotlin bindings deterministic while all document
//! mutation, snapshotting, and CRDT state ownership remain in Rust.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use thiserror::Error;
use yrs::{Doc, GetString, Text, Transact};

static DOCUMENTS: Lazy<RwLock<HashMap<u64, Document>>> = Lazy::new(|| RwLock::new(HashMap::new()));
static NEXT_ID: AtomicU64 = AtomicU64::new(1);
const TEXT_NAME: &str = "content";

struct Document {
    /// Yrs is the authoritative collaborative text state. The cached metadata is
    /// serialized with snapshots and can be applied independently of text edits.
    crdt: Doc,
    spans: Vec<StyledSpan>,
}

impl Default for Document {
    fn default() -> Self {
        Self {
            crdt: Doc::new(),
            spans: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StyledSpan {
    pub start: u32,
    pub end: u32,
    #[serde(default)]
    pub bold: bool,
    #[serde(default)]
    pub italic: bool,
    #[serde(default)]
    pub heading: u8,
    #[serde(default = "default_alignment")]
    pub alignment: Alignment,
    #[serde(default)]
    pub bullet: bool,
}

fn default_alignment() -> Alignment {
    Alignment::Start
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Alignment {
    Start,
    Center,
    End,
    Justify,
}

impl Default for Alignment {
    fn default() -> Self {
        Self::Start
    }
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Request {
    Replace { start: u32, end: u32, text: String },
    SetSpan { span: StyledSpan },
    Load { snapshot: Snapshot },
}

#[derive(Debug, Serialize, Deserialize)]
struct Snapshot {
    text: String,
    spans: Vec<StyledSpan>,
}

#[derive(Debug, Serialize)]
struct Response<'a> {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<&'a str>,
    snapshot: Snapshot,
}

#[derive(Debug, Error)]
enum EngineError {
    #[error("invalid edit: {0}")]
    InvalidEdit(String),
    #[error("invalid request: {0}")]
    InvalidRequest(#[from] serde_json::Error),
}

impl Document {
    fn text(&self) -> String {
        let txn = self.crdt.transact();
        self.crdt.get_or_insert_text(TEXT_NAME).get_string(&txn)
    }

    fn snapshot(&self) -> Snapshot {
        Snapshot {
            text: self.text(),
            spans: self.spans.clone(),
        }
    }

    fn replace(&mut self, start: u32, end: u32, replacement: &str) -> Result<(), EngineError> {
        let current = self.text();
        let length = current.chars().count() as u32;
        if start > end || end > length {
            return Err(EngineError::InvalidEdit(
                "range is outside the document".into(),
            ));
        }
        let mut txn = self.crdt.transact_mut();
        let text = self.crdt.get_or_insert_text(TEXT_NAME);
        text.remove_range(&mut txn, start, end - start);
        if !replacement.is_empty() {
            text.insert(&mut txn, start, replacement);
        }
        let removed = end - start;
        let inserted = replacement.chars().count() as u32;
        for span in &mut self.spans {
            if span.start >= end {
                span.start = span.start.saturating_sub(removed).saturating_add(inserted);
            }
            if span.end >= end {
                span.end = span.end.saturating_sub(removed).saturating_add(inserted);
            }
        }
        self.spans
            .retain(|span| span.start < span.end && span.end <= length - removed + inserted);
        Ok(())
    }

    fn apply(&mut self, request: Request) -> Result<(), EngineError> {
        match request {
            Request::Replace { start, end, text } => self.replace(start, end, &text),
            Request::SetSpan { span } => {
                let length = self.text().chars().count() as u32;
                if span.start >= span.end || span.end > length {
                    return Err(EngineError::InvalidEdit(
                        "style range is outside the document".into(),
                    ));
                }
                self.spans
                    .retain(|old| old.end <= span.start || old.start >= span.end);
                self.spans.push(span);
                Ok(())
            }
            Request::Load { snapshot } => {
                self.replace(0, self.text().chars().count() as u32, &snapshot.text)?;
                self.spans = snapshot.spans;
                Ok(())
            }
        }
    }
}

fn response(document: &Document, error: Option<&str>) -> String {
    serde_json::to_string(&Response { ok: error.is_none(), error, snapshot: document.snapshot() }).unwrap_or_else(|_| "{\"ok\":false,\"error\":\"serialization failure\",\"snapshot\":{\"text\":\"\",\"spans\":[]}}".into())
}

fn java_string(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value)
        .map(|text| text.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_nextgen_editor_NativeBridge_nativeCreateDocument(
    mut env: JNIEnv,
    _: JClass,
) -> jstring {
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    DOCUMENTS.write().insert(id, Document::default());
    java_string(&mut env, id.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_nextgen_editor_NativeBridge_nativeApplyEdit(
    mut env: JNIEnv,
    _: JClass,
    id: JString,
    request: JString,
) -> jstring {
    let id: u64 = env
        .get_string(&id)
        .map(|s| s.into())
        .ok()
        .and_then(|s: String| s.parse().ok())
        .unwrap_or(0);
    let request: String = match env.get_string(&request) {
        Ok(value) => value.into(),
        Err(_) => return java_string(
            &mut env,
            "{\"ok\":false,\"error\":\"bad request\",\"snapshot\":{\"text\":\"\",\"spans\":[]}}"
                .into(),
        ),
    };
    let mut docs = DOCUMENTS.write();
    let Some(document) = docs.get_mut(&id) else {
        return java_string(&mut env, "{\"ok\":false,\"error\":\"document not found\",\"snapshot\":{\"text\":\"\",\"spans\":[]}}".into());
    };
    match serde_json::from_str::<Request>(&request).and_then(|request| {
        document.apply(request).map_err(|error| {
            serde_json::Error::io(std::io::Error::new(std::io::ErrorKind::InvalidInput, error))
        })
    }) {
        Ok(()) => java_string(&mut env, response(document, None)),
        Err(error) => java_string(&mut env, response(document, Some(&error.to_string()))),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nextgen_editor_NativeBridge_nativeSnapshot(
    mut env: JNIEnv,
    _: JClass,
    id: JString,
) -> jstring {
    let id: u64 = env
        .get_string(&id)
        .map(|s| s.into())
        .ok()
        .and_then(|s: String| s.parse().ok())
        .unwrap_or(0);
    let docs = DOCUMENTS.read();
    let result = docs.get(&id).map(|document| response(document, None)).unwrap_or_else(|| "{\"ok\":false,\"error\":\"document not found\",\"snapshot\":{\"text\":\"\",\"spans\":[]}}".into());
    java_string(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_nextgen_editor_NativeBridge_nativeCloseDocument(
    mut env: JNIEnv,
    _: JClass,
    id: JString,
) {
    let id: u64 = env
        .get_string(&id)
        .map(|s| s.into())
        .ok()
        .and_then(|s: String| s.parse().ok())
        .unwrap_or(0);
    DOCUMENTS.write().remove(&id);
}
