use serde::Serialize;

#[cfg(all(
    not(any(target_os = "macos", target_os = "linux", target_os = "windows"))
))]
compile_error!("Platform not supported");

/// Process metrics
/// https://prometheus.io/docs/instrumenting/writing_clientlibs/#process-metrics
#[derive(Debug, Default, PartialEq, Serialize)]
pub struct Metrics {
    pub time_seconds: Option<f64>,
    /// Total user and system CPU time spent in seconds.
    pub cpu_seconds_total: Option<f64>,
    /// Number of open file descriptors.
    pub open_fds: Option<u64>,
    /// Maximum number of open file descriptors.
    /// 0 indicates 'unlimited'.
    pub max_fds: Option<u64>,
    /// Virtual memory size in bytes.
    pub virtual_memory_bytes: Option<u64>,
    /// Maximum amount of virtual memory available in bytes.
    /// 0 indicates 'unlimited'.
    pub virtual_memory_max_bytes: Option<u64>,
    /// Resident memory size in bytes.
    pub resident_memory_bytes: Option<u64>,
    /// Start time of the process since unix epoch in seconds.
    pub start_time_seconds: Option<f64>,
    /// Number of OS threads in the process.
    pub num_threads: Option<u64>,
    /// CPU seconds total for each thread ID
    pub thread_cpu_seconds_total: Option<Vec<(i32, f64)>>,
}
