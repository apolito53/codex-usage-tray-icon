fn main() {
    println!("cargo::rerun-if-changed=build.rs");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        // Set the page size on the final JNI link, including on API 26 builds.
        println!("cargo::rustc-link-arg-cdylib=-Wl,-z,max-page-size=16384");
        println!("cargo::rustc-link-arg-cdylib=-Wl,-z,common-page-size=16384");
    }
}
