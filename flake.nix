{
  description = "Python 3.11 development environment";

  inputs = {
    nixpkgs.url = "github:Nixos/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux"; # Adjust to your system
      pkgs = import nixpkgs { inherit system; };
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        buildInputs = with pkgs; [
          (python3.withPackages (python-pkgs: with python-pkgs; [
            pandas
            numpy
          ]))
          stdenv.cc.cc.lib
          zlib
        ];
      };
    };
}