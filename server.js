#!/usr/local/bin/node
// node-portfinder - https://github.com/indexzero/node-portfinder
var portfinder = require('/usr/local/lib/node_modules/portfinder');
var connect = require('/usr/local/lib/node_modules/connect');
var serveStatic = require('/usr/local/lib/node_modules/serve-static');
var serverPort = 8080;

portfinder.getPort(function (err,port) {
    if (err) {
	console.log ("Could not connect ");
    } else {
	connect().use(serveStatic(__dirname)).listen(port);
	console.log("Node Server is listening on port " + port);
    }});
