# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant::Config.run do |config|
  config.vm.box = "precise64"
  config.vm.box_url = "http://files.vagrantup.com/precise64.box"
  config.vm.share_folder "bootstrap", "/mnt/bootstrap", "Vagrant-setup/share", :create => true
  config.vm.provision :shell, :path => "Vagrant-setup/bootstrap.sh"
  config.vm.forward_port 5432, 15432
end
