package io.heapy.kinetica

/** Single-line string editors. Password is masked by browser and native renderers. */
public enum class TextInputType(public val htmlValue: String) {
    Text("text"),
    Email("email"),
    Password("password"),
    Search("search"),
    Telephone("tel"),
    Url("url"),
}
