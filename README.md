# dashboard-cljs

Clojurescript frontend of Purple internal dashboard

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

Use cljsbuild to build both dev and prod environments together.

```bash
$ lein cljsbuild auto
```

Development environment must be run with a node server using the server.js file.
The server attempts to find an empty port starting from 8080. You must install
the portfinder, connect and server-static node nodules.

```bash
$ sude npm install -g connect serve-static portfinder
```

Run the server

```bash
$ node server.js
Node Server is listening on port 8000
```

Open http://localhost:8000 in your browser


If you would like to open up a separate Chrome dev window for development, use the follwing command:

```bash
$  /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --disable-web-security --user-data-dir=/tmp/chrome2/ \ http://localhost:8000
```
Alternatively, create a script named 'chrome-file-open':

```bash
#!/bin/bash

/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --disable-web-security --user-data-dir=/tmp/chrome2/ \ $1
```

chmod +x this script and put it on your $PATH. You can then open the files with:

```bash
$ chrome-file-open http://localhost:8000
```

The production build is setup to be exported to dashboard-service. Run the server locally
and test the prod build there.

Check both browser tabs as you are developing to ensure that each compilation results in expected behavior. 


The code depends on setting a base-url in the html file that it is served from. For example, index.html and index_release.html both have this div:

```html
<div id="base-url" value="http://localhost:3001/dashboard/" style="display: none;"></div>
```

where the attribute 'value' is the base-url. The clojurescript code pulls the value attribute from div#base-url in order to set the base-url used in server calls.

The dashboard-service server should be running when developing. Start it in the dashboard-service dir:

```bash
$ lein ring server
```

The base-url defined in the div above assumes the server is running on the default port of 3001

## exporting to the server

The dashboard-cljs repository contains a script that will do an advanced compilation of the clojurescript code and copy it to the appropriate location in dashboard-service

```bash
$ ./scripts/export_to_dashboard-service
```

This script assumes that you are developing in a root dir which contains both the dashboard-service and dashboard-cljs repositories. For example, the dir structure that I use for development on my local machine is:

	PurpleInc
	|
	|- dashboard-service
	|
	- dashboard-cljs

## Adapating native React libraries for use with Reagent

Example:  https://github.com/fmoo/react-typeahead

1. Add library to cljsbuild. The react-typeahead.js was found in the dist dir of
the github project.

```clojure
:foreign-libs [;; https://github.com/googlemaps/js-map-label
               {:file "resources/js/maplabel.js"
                :provides ["maplabel"]}
               ;; https://github.com/fmoo/react-typeahead
               {:file "resources/js/react-typeahead.js"
                :provides ["react-typeahead"]}]
```

2. add [react-typeahead] to require statement of namespace it will be used in

3. Create a fn to access it
```clojure
(defn typeahead []
  js/ReactTypeahead.Typeahead.)
```

Note: It is not always immediately clear what the class name is by simply
looking at the javascript. Might have to explore with in the javascript console
as in the above case.

4. adapt the class

```clojure
(def type-ahead (r/adapt-react-class (typeahead)))
```

5. use the class
```clojure
[type-ahead {:options ["Los Angeles" "Orange County"
                       "Seattle" "San Diego"]
             :maxVisible 2}]
```
## License


Copyright © 2016 Purple Services Inc
