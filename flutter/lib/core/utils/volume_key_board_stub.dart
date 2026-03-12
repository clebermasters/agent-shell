// Stub for web — volume_key_board is Android/iOS only
enum VolumeKey { up, down }

class VolumeKeyBoard {
  static final VolumeKeyBoard instance = VolumeKeyBoard._();
  VolumeKeyBoard._();
  void addListener(void Function(VolumeKey) listener) {}
  void removeListener() {}
}
