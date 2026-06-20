//! In-app update checking against GitHub Releases (through the tunnel).
//!
//! The check fetches the project's latest published release from the GitHub API
//! *via the WARP tunnel*, so the update poll never reveals the user's real IP.
//! `releases/latest` already excludes drafts and pre-releases, matching the
//! project's stable `vMAJOR.MINOR.PATCH` tag scheme.

use crate::config::FetchLimits;
use crate::error::ProxyError;
use crate::http::FetchOutcome;
use crate::tunnel::TunnelManager;
use serde::Deserialize;

/// Canonical GitHub repository slug for the official distribution channel.
pub const DEFAULT_REPO: &str = "BTreeMap/Letterbox";

/// Result of an update check.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateInfo {
    /// Whether the latest release is newer than the running version.
    pub update_available: bool,
    /// The currently running version (as supplied by the caller).
    pub current_version: String,
    /// Latest release version (tag without a leading `v`).
    pub latest_version: String,
    /// Latest release git tag (e.g. `v1.2.3`).
    pub latest_tag: String,
    /// Release notes / changelog body.
    pub changelog: String,
    /// URL of the release page.
    pub release_url: String,
}

/// Minimal subset of the GitHub release JSON we consume.
#[derive(Debug, Deserialize)]
struct GithubRelease {
    tag_name: String,
    #[serde(default)]
    body: String,
    #[serde(default)]
    html_url: String,
    #[serde(default)]
    prerelease: bool,
    #[serde(default)]
    draft: bool,
}

/// A parsed `MAJOR.MINOR.PATCH` version triple for ordering.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
struct SemVer {
    major: u32,
    minor: u32,
    patch: u32,
}

impl SemVer {
    /// Parse a version that may carry a `v` prefix and `-pre`/`+build` suffixes.
    fn parse(raw: &str) -> Option<Self> {
        let trimmed = raw.trim().trim_start_matches(['v', 'V']);
        // Drop any pre-release / build metadata after the core triple.
        let core = trimmed.split(['-', '+']).next().unwrap_or(trimmed);
        let mut parts = core.split('.');
        let major = parts.next()?.parse().ok()?;
        let minor = parts.next().unwrap_or("0").parse().ok()?;
        let patch = parts.next().unwrap_or("0").parse().ok()?;
        if parts.next().is_some() {
            return None;
        }
        Some(SemVer {
            major,
            minor,
            patch,
        })
    }
}

/// Check for a newer release of `repo` than `current_version` via the tunnel.
pub fn check_for_update(
    manager: &TunnelManager,
    current_version: &str,
    repo: &str,
) -> Result<UpdateInfo, ProxyError> {
    let url = format!("https://api.github.com/repos/{repo}/releases/latest");
    let headers = vec![
        (
            "User-Agent".to_string(),
            "Letterbox-UpdateChecker".to_string(),
        ),
        ("X-GitHub-Api-Version".to_string(), "2022-11-28".to_string()),
    ];
    let limits = FetchLimits {
        max_size: 1024 * 1024, // release JSON is small
        ..FetchLimits::default()
    };

    let outcome: FetchOutcome = manager.fetch(
        url,
        headers,
        "application/vnd.github+json".to_string(),
        limits,
    )?;

    let release: GithubRelease =
        serde_json::from_slice(&outcome.body).map_err(|e| ProxyError::HttpError {
            status_code: outcome.status,
            details: format!("Failed to parse release JSON: {e}"),
        })?;

    if release.draft || release.prerelease {
        // `releases/latest` should never return these, but guard anyway.
        return Ok(UpdateInfo {
            update_available: false,
            current_version: current_version.to_string(),
            latest_version: release.tag_name.trim_start_matches(['v', 'V']).to_string(),
            latest_tag: release.tag_name,
            changelog: release.body,
            release_url: release.html_url,
        });
    }

    let update_available = match (
        SemVer::parse(current_version),
        SemVer::parse(&release.tag_name),
    ) {
        (Some(current), Some(latest)) => latest > current,
        // If we cannot parse the running version, fail closed (no false alarms).
        _ => false,
    };

    Ok(UpdateInfo {
        update_available,
        current_version: current_version.to_string(),
        latest_version: release.tag_name.trim_start_matches(['v', 'V']).to_string(),
        latest_tag: release.tag_name,
        changelog: release.body,
        release_url: release.html_url,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_versions_with_prefix_and_suffix() {
        assert_eq!(
            SemVer::parse("v1.2.3"),
            Some(SemVer {
                major: 1,
                minor: 2,
                patch: 3
            })
        );
        assert_eq!(
            SemVer::parse("1.2.4-dev.5+abcdef"),
            Some(SemVer {
                major: 1,
                minor: 2,
                patch: 4
            })
        );
        assert_eq!(
            SemVer::parse("2.0"),
            Some(SemVer {
                major: 2,
                minor: 0,
                patch: 0
            })
        );
    }

    #[test]
    fn rejects_invalid_versions() {
        assert_eq!(SemVer::parse("not-a-version"), None);
        assert_eq!(SemVer::parse("1.2.3.4"), None);
        assert_eq!(SemVer::parse(""), None);
    }

    #[test]
    fn orders_versions_correctly() {
        assert!(SemVer::parse("v1.2.4").unwrap() > SemVer::parse("v1.2.3").unwrap());
        assert!(SemVer::parse("v2.0.0").unwrap() > SemVer::parse("v1.9.9").unwrap());
        assert!(SemVer::parse("v1.3.0").unwrap() > SemVer::parse("v1.2.9").unwrap());
        assert_eq!(
            SemVer::parse("v1.2.3").unwrap(),
            SemVer::parse("1.2.3").unwrap()
        );
    }
}
