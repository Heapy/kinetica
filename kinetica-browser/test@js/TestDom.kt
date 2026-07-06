package io.heapy.kinetica.browser

// Reinstall a fresh document on every call so sequential tests do not share DOM state.
internal fun installTestDocument() {
    js(
        """
        (function () {
          function escapeText(value) {
            return String(value)
              .replace(/&/g, "&amp;")
              .replace(/</g, "&lt;")
              .replace(/>/g, "&gt;");
          }
          function escapeAttribute(value) {
            return escapeText(value).replace(/"/g, "&quot;");
          }
          function TestNode() {
            this.parentNode = null;
          }
          Object.defineProperty(TestNode.prototype, "nextSibling", {
            get: function () {
              if (this.parentNode == null) return null;
              var siblings = this.parentNode._children;
              var index = siblings.indexOf(this);
              return index < 0 || index + 1 >= siblings.length ? null : siblings[index + 1];
            },
          });
          Object.defineProperty(TestNode.prototype, "isConnected", {
            get: function () {
              return this.parentNode != null;
            },
          });
          function TestText(value) {
            TestNode.call(this);
            this._value = String(value);
          }
          TestText.prototype = Object.create(TestNode.prototype);
          TestText.prototype.constructor = TestText;
          Object.defineProperty(TestText.prototype, "nodeValue", {
            get: function () {
              return this._value;
            },
            set: function (value) {
              this._value = String(value);
            },
          });
          Object.defineProperty(TestText.prototype, "textContent", {
            get: function () {
              return this._value;
            },
            set: function (value) {
              this._value = String(value);
            },
          });
          Object.defineProperty(TestText.prototype, "outerHTML", {
            get: function () {
              return escapeText(this._value);
            },
          });
          function TestElement(tagName, ownerDocument) {
            var self = this;
            TestNode.call(this);
            this.tagName = String(tagName).toUpperCase();
            this.ownerDocument = ownerDocument;
            this._attributes = new Map();
            this._children = [];
            this.childNodes = {
              item: function (index) {
                return self._children[index] || null;
              },
            };
            Object.defineProperty(this.childNodes, "length", {
              get: function () {
                return self._children.length;
              },
            });
            this.children = {
              item: function (index) {
                var elements = self._children.filter(function (child) {
                  return child instanceof TestElement;
                });
                return elements[index] || null;
              },
            };
            Object.defineProperty(this.children, "length", {
              get: function () {
                return self._children.filter(function (child) {
                  return child instanceof TestElement;
                }).length;
              },
            });
          }
          TestElement.prototype = Object.create(TestNode.prototype);
          TestElement.prototype.constructor = TestElement;
          Object.defineProperty(TestElement.prototype, "firstChild", {
            get: function () {
              return this._children[0] || null;
            },
          });
          Object.defineProperty(TestElement.prototype, "nextElementSibling", {
            get: function () {
              var sibling = this.nextSibling;
              while (sibling != null && !(sibling instanceof TestElement)) {
                sibling = sibling.nextSibling;
              }
              return sibling;
            },
          });
          TestElement.prototype.insertBefore = function (child, anchor) {
            if (child.parentNode != null) {
              child.parentNode.removeChild(child);
            }
            var index = anchor == null ? -1 : this._children.indexOf(anchor);
            if (index < 0) {
              this._children.push(child);
            } else {
              this._children.splice(index, 0, child);
            }
            child.parentNode = this;
            return child;
          };
          TestElement.prototype.removeChild = function (child) {
            var index = this._children.indexOf(child);
            if (index < 0) throw new Error("Child not found.");
            this._children.splice(index, 1);
            child.parentNode = null;
            return child;
          };
          TestElement.prototype.setAttribute = function (name, value) {
            this._attributes.set(String(name), String(value));
          };
          TestElement.prototype.getAttribute = function (name) {
            return this._attributes.has(String(name)) ? this._attributes.get(String(name)) : null;
          };
          TestElement.prototype.removeAttribute = function (name) {
            this._attributes.delete(String(name));
          };
          TestElement.prototype.addEventListener = function () {};
          TestElement.prototype.removeEventListener = function () {};
          TestElement.prototype.querySelector = function () {
            return null;
          };
          Object.defineProperty(TestElement.prototype, "textContent", {
            get: function () {
              return this._children.map(function (child) {
                return child.textContent;
              }).join("");
            },
            set: function (value) {
              this._children.forEach(function (child) {
                child.parentNode = null;
              });
              this._children = value === "" ? [] : [new TestText(value)];
              var self = this;
              this._children.forEach(function (child) {
                child.parentNode = self;
              });
            },
          });
          Object.defineProperty(TestElement.prototype, "innerHTML", {
            get: function () {
              return this._children.map(function (child) {
                return child.outerHTML;
              }).join("");
            },
          });
          Object.defineProperty(TestElement.prototype, "outerHTML", {
            get: function () {
              var attrs = Array.from(this._attributes.entries())
                .map(function (entry) {
                  return " " + entry[0] + "=\"" + escapeAttribute(entry[1]) + "\"";
                })
                .join("");
              var tag = this.tagName.toLowerCase();
              return "<" + tag + attrs + ">" + this.innerHTML + "</" + tag + ">";
            },
          });
          var document = {
            body: null,
            activeElement: null,
            createElement: function (tagName) {
              return new TestElement(tagName, document);
            },
            createTextNode: function (value) {
              return new TestText(value);
            },
            querySelector: function () {
              return null;
            },
          };
          document.body = document.createElement("body");
          document.activeElement = document.body;
          globalThis.Element = TestElement;
          globalThis.document = document;
        }());
        """,
    )
}

internal fun testDocument(): dynamic =
    js("document")
