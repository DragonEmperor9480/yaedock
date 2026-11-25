//! What to install for each part of YaeDock, named for each package manager,
//! so `yaedock check` can give the one command that installs it.

/// A package manager this knows the package names for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Manager {
    /// Arch, CachyOS, Manjaro, EndeavourOS.
    Pacman,
    /// Debian, Ubuntu, Linux Mint, Pop!_OS.
    Apt,
    /// Fedora, RHEL and its rebuilds.
    Dnf,
    /// openSUSE.
    Zypper,
    /// Gentoo: only the PCI ID list is named for it.
    Emerge,
}

impl Manager {
    /// The package manager on this PC, found on `PATH`.
    pub fn detect() -> Option<Self> {
        let path = std::env::var_os("PATH")?;
        Self::detect_with(|program| {
            std::env::split_paths(&path).any(|dir| dir.join(program).is_file())
        })
    }

    pub fn detect_with(has: impl Fn(&str) -> bool) -> Option<Self> {
        [
            ("pacman", Manager::Pacman),
            ("apt-get", Manager::Apt),
            ("dnf", Manager::Dnf),
            ("zypper", Manager::Zypper),
            ("emerge", Manager::Emerge),
        ]
        .into_iter()
        .find(|(program, _)| has(program))
        .map(|(_, manager)| manager)
    }

    /// The command that installs `packages`, skipping any already installed.
    pub fn install(self, packages: &[&str]) -> String {
        let packages = packages.join(" ");
        match self {
            Manager::Pacman => format!("sudo pacman -S --needed {packages}"),
            Manager::Apt => format!("sudo apt-get install {packages}"),
            Manager::Dnf => format!("sudo dnf install {packages}"),
            Manager::Zypper => format!("sudo zypper install {packages}"),
            Manager::Emerge => format!("sudo emerge --noreplace {packages}"),
        }
    }
}

/// Something a part of YaeDock needs installed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Need {
    /// GStreamer for the PC's screen on the phone and the phone's camera: its
    /// tools, the base, good and bad plugins, its PipeWire plugin, and H.264
    /// encoders and decoders for any graphics card or none.
    GStreamer,
    /// `pactl`, to pick the audio output and follow volume changes.
    Pactl,
    /// PipeWire's PulseAudio server and `pactl`, which the phone's microphone
    /// and speaker are made with.
    PulseAudio,
    /// libopus, so the phone can send its sound compressed.
    Opus,
    /// A power profile service.
    PowerProfiles,
    /// Spectacle, for silent screenshots.
    Screenshots,
    /// OBS Studio, for recording the screen.
    Obs,
    /// The PCI ID list, for GPU names.
    PciIds,
}

impl Need {
    /// Every need, so a test can find each in the README.
    #[cfg(test)]
    pub const ALL: [Need; 8] = [
        Need::GStreamer,
        Need::PulseAudio,
        Need::Pactl,
        Need::Opus,
        Need::PowerProfiles,
        Need::Screenshots,
        Need::Obs,
        Need::PciIds,
    ];

    /// The packages that meet this need with `manager`, or None where this
    /// does not know them.
    pub fn packages(self, manager: Manager) -> Option<&'static [&'static str]> {
        use Manager::*;
        Some(match (self, manager) {
            (Need::GStreamer, Pacman) => &[
                "gstreamer",
                "gst-plugins-base",
                "gst-plugins-good",
                "gst-plugins-bad",
                "gst-plugin-pipewire",
                "gst-plugin-va",
                "gst-libav",
                "gst-plugins-ugly",
            ],
            // The VA-API plugin is in plugins-bad here.
            (Need::GStreamer, Apt) => &[
                "gstreamer1.0-tools",
                "gstreamer1.0-plugins-base",
                "gstreamer1.0-plugins-good",
                "gstreamer1.0-plugins-bad",
                "gstreamer1.0-pipewire",
                "gstreamer1.0-libav",
                "gstreamer1.0-plugins-ugly",
            ],
            // Fedora's own H.264 is OpenH264, which encodes and decodes.
            (Need::GStreamer, Dnf) => &[
                "gstreamer1",
                "gstreamer1-plugins-base",
                "gstreamer1-plugins-good",
                "gstreamer1-plugins-bad-free",
                "pipewire-gstreamer",
                "gstreamer1-plugin-openh264",
            ],
            (Need::GStreamer, Zypper) => &[
                "gstreamer-utils",
                "gstreamer-plugins-base",
                "gstreamer-plugins-good",
                "gstreamer-plugins-bad",
                "gstreamer-plugin-pipewire",
                "gstreamer-plugin-openh264",
            ],
            (Need::Pactl, Pacman) => &["libpulse"],
            (Need::Pactl, Apt | Dnf | Zypper) => &["pulseaudio-utils"],
            (Need::PulseAudio, Pacman) => &["pipewire-pulse", "libpulse"],
            (Need::PulseAudio, Apt) => &["pipewire-pulse", "pulseaudio-utils"],
            (Need::PulseAudio, Dnf | Zypper) => &["pipewire-pulseaudio", "pulseaudio-utils"],
            (Need::Opus, Pacman | Dnf) => &["opus"],
            (Need::Opus, Apt | Zypper) => &["libopus0"],
            (Need::PowerProfiles, Pacman | Apt | Zypper) => &["power-profiles-daemon"],
            (Need::PowerProfiles, Dnf) => &["tuned-ppd"],
            (Need::Screenshots, Pacman | Dnf | Zypper) => &["spectacle"],
            (Need::Screenshots, Apt) => &["kde-spectacle"],
            (Need::Obs, Pacman | Apt | Dnf | Zypper) => &["obs-studio"],
            (Need::PciIds, Emerge) => &["sys-apps/hwdata"],
            (Need::PciIds, _) => &["hwdata"],
            (_, Emerge) => return None,
        })
    }
}

/// The command that installs `needs` on this PC, or None when its package
/// manager is not one this knows.
pub fn install_command(needs: &[Need]) -> Option<String> {
    install_command_with(Manager::detect()?, needs)
}

pub fn install_command_with(manager: Manager, needs: &[Need]) -> Option<String> {
    let mut packages: Vec<&str> = Vec::new();
    for need in needs {
        for package in need.packages(manager)? {
            if !packages.contains(package) {
                packages.push(package);
            }
        }
    }
    Some(manager.install(&packages))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_the_package_manager_on_the_path() {
        let only = |name: &'static str| move |program: &str| program == name;
        assert_eq!(Manager::detect_with(only("pacman")), Some(Manager::Pacman));
        assert_eq!(Manager::detect_with(only("apt-get")), Some(Manager::Apt));
        assert_eq!(Manager::detect_with(only("dnf")), Some(Manager::Dnf));
        assert_eq!(Manager::detect_with(only("zypper")), Some(Manager::Zypper));
        assert_eq!(Manager::detect_with(only("emerge")), Some(Manager::Emerge));
        assert_eq!(Manager::detect_with(|_| false), None);
    }

    #[test]
    fn installs_with_each_package_manager() {
        assert_eq!(
            install_command_with(Manager::Pacman, &[Need::PciIds]).as_deref(),
            Some("sudo pacman -S --needed hwdata")
        );
        assert_eq!(
            install_command_with(Manager::Apt, &[Need::Opus]).as_deref(),
            Some("sudo apt-get install libopus0")
        );
        assert_eq!(
            install_command_with(Manager::Dnf, &[Need::PowerProfiles]).as_deref(),
            Some("sudo dnf install tuned-ppd")
        );
        assert_eq!(
            install_command_with(Manager::Zypper, &[Need::Screenshots]).as_deref(),
            Some("sudo zypper install spectacle")
        );
        assert_eq!(
            install_command_with(Manager::Emerge, &[Need::PciIds]).as_deref(),
            Some("sudo emerge --noreplace sys-apps/hwdata")
        );
        assert_eq!(
            install_command_with(Manager::Emerge, &[Need::GStreamer]),
            None
        );
    }

    #[test]
    fn names_each_package_once() {
        let command =
            install_command_with(Manager::Pacman, &[Need::PulseAudio, Need::Pactl]).unwrap();
        assert_eq!(command, "sudo pacman -S --needed pipewire-pulse libpulse");
    }

    #[test]
    fn gstreamer_brings_its_tools_pipewire_and_h264() {
        for manager in [Manager::Pacman, Manager::Apt, Manager::Dnf, Manager::Zypper] {
            let packages = Need::GStreamer.packages(manager).unwrap().join(" ");
            assert!(packages.contains("pipewire"), "{manager:?}: {packages}");
            assert!(packages.contains("good"), "{manager:?}: {packages}");
            assert!(packages.contains("bad"), "{manager:?}: {packages}");
            assert!(
                ["libav", "openh264", "ugly", "-va"]
                    .iter()
                    .any(|h264| packages.contains(h264)),
                "{manager:?}: {packages}"
            );
        }
    }

    #[test]
    fn the_readme_lists_every_package() {
        let readme = include_str!("../../../README.md");
        for manager in [Manager::Pacman, Manager::Apt, Manager::Dnf, Manager::Zypper] {
            for need in Need::ALL {
                for package in need.packages(manager).unwrap() {
                    assert!(
                        readme.contains(&format!(" {package}")),
                        "README lacks {package} for {need:?} with {manager:?}"
                    );
                }
            }
        }
    }
}
