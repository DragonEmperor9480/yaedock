//! Whether the firewall lets phones reach the daemon, found without root where
//! possible, and the one command that opens the port when it does not.

use std::fs;
use std::process::{Command, Stdio};

/// What the firewall does with a port.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Verdict {
    /// No firewall is active, so nothing blocks it.
    NoFirewall,
    Allowed {
        firewall: &'static str,
    },
    Blocked {
        firewall: &'static str,
    },
    /// A firewall is on, but its rules could not be read without root.
    Unknown {
        firewall: &'static str,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Proto {
    Tcp,
    Udp,
}

impl Proto {
    fn name(self) -> &'static str {
        match self {
            Proto::Tcp => "tcp",
            Proto::Udp => "udp",
        }
    }
}

fn active(unit: &str) -> bool {
    Command::new("systemctl")
        .args(["is-active", "--quiet", unit])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|s| s.success())
}

/// Finds out whether incoming `proto` traffic on `port` is allowed.
pub fn verdict(port: u16, proto: Proto) -> Verdict {
    if active("ufw") {
        let enabled = fs::read_to_string("/etc/ufw/ufw.conf")
            .map(|conf| conf.lines().any(|l| l.trim() == "ENABLED=yes"))
            .unwrap_or(true);
        if !enabled {
            return Verdict::NoFirewall;
        }
        let rules = ["/etc/ufw/user.rules"]
            .iter()
            .map(fs::read_to_string)
            .collect::<Result<Vec<_>, _>>();
        let policy = fs::read_to_string("/etc/default/ufw").ok();
        return match (rules, policy) {
            (Ok(rules), Some(policy)) => {
                if ufw_allows(&rules.concat(), &policy, port, proto) {
                    Verdict::Allowed { firewall: "ufw" }
                } else {
                    Verdict::Blocked { firewall: "ufw" }
                }
            }
            _ => Verdict::Unknown { firewall: "ufw" },
        };
    }
    if active("firewalld") {
        let query = Command::new("firewall-cmd")
            .arg(format!("--query-port={port}/{}", proto.name()))
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        return match query.map(|s| s.code()) {
            Ok(Some(0)) => Verdict::Allowed {
                firewall: "firewalld",
            },
            Ok(Some(1)) => Verdict::Blocked {
                firewall: "firewalld",
            },
            _ => Verdict::Unknown {
                firewall: "firewalld",
            },
        };
    }
    Verdict::NoFirewall
}

/// Applies ufw's logic: the first rule matching the port decides, otherwise
/// the default incoming policy does.
fn ufw_allows(rules: &str, defaults: &str, port: u16, proto: Proto) -> bool {
    // Rules are recorded as "### tuple ### allow tcp 9481 0.0.0.0/0 any 0.0.0.0/0 in".
    for line in rules.lines() {
        let Some(tuple) = line.strip_prefix("### tuple ### ") else {
            continue;
        };
        let fields: Vec<&str> = tuple.split_whitespace().collect();
        let [
            action,
            rule_proto,
            dports,
            _dst,
            _sport,
            _src,
            direction,
            ..,
        ] = fields.as_slice()
        else {
            continue;
        };
        if !direction.starts_with("in") || action.starts_with("route") {
            continue;
        }
        if *rule_proto != "any" && *rule_proto != proto.name() {
            continue;
        }
        if !ports_contain(dports, port) {
            continue;
        }
        return matches!(*action, "allow" | "limit");
    }
    let policy = defaults
        .lines()
        .find_map(|l| l.trim().strip_prefix("DEFAULT_INPUT_POLICY="))
        .map(|v| v.trim_matches('"'))
        .unwrap_or("DROP");
    policy == "ACCEPT"
}

/// Whether a ufw port spec ("9481", "1714:1764", "80,443", "any") includes `port`.
fn ports_contain(spec: &str, port: u16) -> bool {
    spec == "any"
        || spec.split(',').any(|part| match part.split_once(':') {
            Some((low, high)) => {
                low.parse::<u16>().is_ok_and(|l| l <= port)
                    && high.parse::<u16>().is_ok_and(|h| port <= h)
            }
            None => part.parse() == Ok(port),
        })
}

/// The command that opens the port, for the firewall that is on.
pub fn allow_command(firewall: &str, port: u16, proto: Proto) -> String {
    match firewall {
        "firewalld" => format!(
            "sudo firewall-cmd --permanent --add-port={port}/{p} && sudo firewall-cmd --reload",
            p = proto.name()
        ),
        _ => format!("sudo ufw allow {port}/{}", proto.name()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const RULES: &str = "\
*filter
### tuple ### allow tcp 1714:1764 0.0.0.0/0 any 0.0.0.0/0 in
-A ufw-user-input -p tcp -m multiport --dports 1714:1764 -j ACCEPT
### tuple ### allow udp 5353 0.0.0.0/0 any 0.0.0.0/0 in
### tuple ### deny tcp 22 0.0.0.0/0 any 0.0.0.0/0 in
### tuple ### route:allow any any 0.0.0.0/0 any 0.0.0.0/0 in_wlan0!out_eth0
### tuple ### allow tcp 9481 0.0.0.0/0 any 0.0.0.0/0 in
";
    const DROP: &str = "DEFAULT_INPUT_POLICY=\"DROP\"\n";

    #[test]
    fn reads_ufw_rules() {
        assert!(ufw_allows(RULES, DROP, 9481, Proto::Tcp));
        assert!(ufw_allows(RULES, DROP, 1720, Proto::Tcp));
        assert!(ufw_allows(RULES, DROP, 5353, Proto::Udp));
        assert!(!ufw_allows(RULES, DROP, 5353, Proto::Tcp));
        assert!(!ufw_allows(RULES, DROP, 22, Proto::Tcp));
        assert!(!ufw_allows(RULES, DROP, 9482, Proto::Tcp));
        assert!(ufw_allows(
            "",
            "DEFAULT_INPUT_POLICY=\"ACCEPT\"",
            9482,
            Proto::Tcp
        ));
    }

    #[test]
    fn port_specs() {
        assert!(ports_contain("any", 1));
        assert!(ports_contain("80,443,9481", 9481));
        assert!(!ports_contain("80,443", 9481));
        assert!(ports_contain("9000:9999", 9481));
    }
}
