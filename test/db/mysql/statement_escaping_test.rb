require 'db/mysql'
require 'models/entry'

class MySQLStatementEscapingTest < Test::Unit::TestCase

  def setup
    super; EntryMigration.up

    @_config_ = current_connection_config.dup
    ActiveRecord::Base.clear_active_connections!
  end

  def teardown
    return unless @_config_

    ActiveRecord::Base.clear_active_connections!
    ActiveRecord::Base.establish_connection @_config_

    EntryMigration.down; super
  end

  def test_set_to_false
    set_escape_processing false
    e1 = Entry.create! :title => "\\'{}{"
    e2 = Entry.find(e1.id)
    assert_equal "\\'{}{", e2.title
  end

  def test_set_to_true
    set_escape_processing true
    verify_escaped
  end

  def test_not_set
    set_escape_processing nil
    verify_escaped
  end

  private

  def config; @_config_ ||= nil; end

  def set_escape_processing(value)
    ActiveRecord::Base.establish_connection config.merge(:statement_escape_processing => value)
  end

  def verify_escaped
    pend unless defined? JRUBY_VERSION

    e = Entry.create! :title => 'abc'
    rs = ActiveRecord::Base.connection.execute(
        "SELECT {fn concat(title, 'xyz')} AS title from entries WHERE id = #{e.id}"
    )
    # TODO Mysql2 returns : #<Mysql2::Result:0x000000036e06d8>
    # first: ["abcxyz"]
    # while ArJdbc : [{"title"=>"abcxyz"}]
    # {"title"=>"abcxyz"}
    assert_equal 'abcxyz', rs.first['title']
  end

end