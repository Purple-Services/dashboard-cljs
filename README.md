# dashboard-cljs

Clojurescript version of the web-service dashboard

## Setup

When developing, both basic and advanced compilation should be done in parallel and tested. This is because there can be discrepancies in the way the code is compiled between the two modes. One common problem is the way javascript object properties are accessed in clojurescript. For example, in javascript the URL property of document is accessed via:

```javascript
document.URL
```

In clojurescript, this can be accessed in two ways.

```clojurescript
;; first example
(.-URL document)
;; second example
(aget document "URL")
```

These two statements are equivalent when using basic compilation. However, in advanced compilation mode the first example will fail due to name mangling. The reason that the second example works and the first one doesn't is that the Google closure compiler in advanced compilation mode (which is used by the clojurescript compiler) will not touch strings.

Production code should use the output from advanced compilation. When developing, the basic compilation output is more useful for debugging purposes. It is therefore imperative that both the basic and advanced compilations are developed and tested in parallel.

A common workflow is that a new feature is implemented and tested in basic mode. Once it is working to your satisfaction, test that the advanced compilation of the code works the same way.

There are two scripts which will watch your "src" dir and recompile if there are any changes made to the clojurescript source file.

Currently, these scripts must be run in two separate terminal instances. In one terminal:

	./scripts/watch

In another terminal:

	./scripts/watch_release

Open both `index.html` and `index_release.html` in your browser. Use the following chrome command:

	/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --disable-web-security --user-data-dir=/tmp/chrome2/ \ index.html

Alternatively, create a script named 'chrome-open-file':

```bash
#!/bin/bash

/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --disable-web-security --user-data-dir=/tmp/chrome2/ \ $1
```

chmod +x this script and put it on your $PATH. You can then open the files with:

	chrome-open-file index.html

	chrome-open-file index_release.html

Check both browser tabs as you are developing to ensure that each compilation results in expected behavior. 


The code depends on setting a base-url in the html file that it is served from. For example, index.html and index_release.html both have this div:

```html
<div id="base-url" value="http://localhost:3000/dashboard/" style="display: none;"></div>
```

where the attribute 'value' is the base-url. The clojurescript code pulls the value attribute from div#base-url in order to set the base-url used in server calls.

## exporting to the server

The dashboard-cljs repository contains a script that will do an advanced compilation of the clojurescript code and copy it to the appropriate location in web-service

	 ./scripts/export_to_web-service

This script assumes that you are developing in a root dir which contains both the web-service and dashboard-cljs repositories. For example, the dir structure that I use for development on my local machine is:

	PurpleInc
	|
	|- web-service
	|
	 - dashboard-cljs


## License

Copyright © 2015 Purple Services Inc
