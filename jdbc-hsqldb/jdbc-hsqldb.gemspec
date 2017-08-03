# -*- encoding: utf-8 -*-
$LOAD_PATH << File.expand_path('../lib', __FILE__)
require 'jdbc/hsqldb/version'

Gem::Specification.new do |gem|
  gem.name = %q{jdbc-hsqldb}
  gem.version = Jdbc::HSQLDB::VERSION

  gem.authors = ['Nick Sieger, Ola Bini, Karol Bucek and JRuby contributors']
  gem.email = ['nick@nicksieger.com', 'ola.bini@gmail.com', 'self@kares.org']
  gem.homepage = 'http://github.com/jruby/activerecord-jdbc-adapter/tree/master/jdbc-hsqldb'
  gem.licenses = ['BSD']

  Dir.chdir(File.dirname(__FILE__)) { gem.files = `git ls-files`.split("\n") }

  gem.rdoc_options = ["--main", "README.md"]
  gem.require_paths = ["lib"]

  gem.summary = %q{HSQLDB (JDBC driver) for JRuby (usable with ActiveRecord-JDBC).}
  gem.description = %q{Install this gem `require 'jdbc/hsqldb'` and invoke `Jdbc::HSQLDB.load_driver` within JRuby to load the driver.}
end
