module ArJdbc

  # Defines an AR-JDBC extension. An extension consists of a declaration using
  # this method and an `ArJdbc::XYZ` module that contains implementation and
  # overrides (for `ActiveRecord::ConnectionAdapters::AbstractAdapter` methods).
  #
  # When you declare your extension, you provide a block that detects when a
  # database configured to use the extension is present and loads the necessary
  # code for it. AR-JDBC will patch the code into the base `JdbcAdapter` by
  # extending an instance of it with your extension module.
  #
  # @note This functionality is usually no longer needed and using ActiveRecord
  # conventions (exporting a `xyz_connection` methods for `adapter: xyz`) should
  # be preferred instead.
  #
  # @param name the name of a module to be defined under {ArJdbc}
  # @param block should be a one or two-arity initialization code block that
  # receives the dialect name or driver class name as the first argument, and
  # optionally the database configuration hash as a second argument
  # ```ruby
  #   ArJdbc.extension :MegaDB do |name|
  #     if name =~ /mega/i # driver or DB name returned by driver
  #       require 'arjdbc/megadb' # contains ArJdbc::MegaDB
  #       true
  #     end
  #   end
  # ```
  def self.extension(name, &block)
    if const_defined?(name)
      mod = const_get(name)
    else
      mod = const_set(name, Module.new)
    end
    (class << mod; self; end).instance_eval do
      define_method :adapter_matcher do |_name, config|
        if block.arity == 1
          block.call(_name) ? mod : false
        else
          block.call(_name, config) ? mod : false
        end
      end
    end unless mod.respond_to?(:adapter_matcher)
  end

  def self.discover_extensions
    if defined?(Gem) && Gem.respond_to?(:find_files)
      arjdbc_path = Gem.loaded_specs['activerecord-jdbc-adapter'].full_gem_path
      files = Gem.find_files('arjdbc/discover').map do |path|
        # in case multiple adapter gems installed only "self" discovery :
        if path =~ /activerecord\-jdbc\-adapter\-.*\/lib\/arjdbc\/discover\.rb/
          path.start_with?(arjdbc_path) ? path : nil
        else
          path
        end
      end
    else
      files = $LOAD_PATH.map do |path|
        discover = File.join(path, 'arjdbc', 'discover.rb')
        File.exist?(discover) ? discover : nil
      end
    end
    files.each do |file|
      next unless file
      puts "loading AR-JDBC extension #{file}" if $DEBUG
      require file
    end
  end

end
